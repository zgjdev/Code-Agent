package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.RoutingSource;
import com.codeagent.history.*;
import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeExecutionHostRecoveryTest {
    @TempDir Path temporary;
    @Test void rejectedOwnerDoesNotInitializeRuntimeOrRecoverRunningRow() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace"));
        WorkspaceIdentity identity = new WorkspaceIdentityResolver().resolve(root);
        Path locks = temporary.resolve("locks");
        try (var store = new RuntimeExecutionStore(temporary.resolve("execution.db"));
             var owner = WorkspaceOwnerLease.acquire(locks, identity, "other-runtime");
             var host = new RuntimeExecutionHost(store, RuntimeExecutionOptions.defaults(), ExecutionEventSink.discarding(), locks)) {
            RuntimeExecution execution = store.enqueue(root, "session", "读取文件", ExecutionMode.REACT);
            execution = store.claimCandidate(owner, execution.id()).orElseThrow();
            assertThrows(java.io.IOException.class, () -> host.registerWorkspace(root, (workspace, events) -> {
                throw new AssertionError("Runtime must not be initialized before ownership");
            }));
            assertEquals(execution, store.find(execution.id()).orElseThrow());
        }
    }

    @Test void failedUnregistrationRejectsSubmissionAndCanBeRetried() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace"));
        try (SessionStore sessions = SessionStore.open(temporary.resolve("history"));
             RuntimeExecutionStore store = new RuntimeExecutionStore(temporary.resolve("execution.db"));
             RuntimeExecutionHost host = new RuntimeExecutionHost(store, RuntimeExecutionOptions.defaults(),
                     ExecutionEventSink.discarding(), temporary.resolve("locks"))) {
            DefaultWorkspaceRuntimeFactory delegate = new DefaultWorkspaceRuntimeFactory(store, sessions, identity -> new NoCallClient());
            java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
            WorkspaceRuntimeFactory factory = (identity, events) -> {
                WorkspaceRuntime runtime = delegate.create(identity, events);
                runtime.setBeforeClose(() -> { if (fail.getAndSet(false)) throw new IllegalStateException("Injected close failure"); });
                return runtime;
            };
            WorkspaceIdentity workspace = host.registerWorkspace(root, factory);
            String session = host.createSession(workspace.key());
            assertThrows(IllegalStateException.class, () -> host.unregisterWorkspace(workspace.key()));
            assertThrows(IllegalStateException.class, () -> host.submit(session, "读取文件", ExecutionMode.REACT));
            assertThrows(IllegalStateException.class, () -> host.registerWorkspace(root, factory));
            assertTrue(store.workspaceExecutions(workspace.key()).isEmpty());
            host.unregisterWorkspace(workspace.key());
            assertEquals(workspace, host.registerWorkspace(root, delegate));
        }
    }

    @Test void reconcilesLedgerEndBeforeRequeueAndDoesNotRecoverAnotherWorkspace() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("a"));
        Path other = Files.createDirectory(temporary.resolve("b"));
        Path database = temporary.resolve("execution.db");
        try (SessionStore sessions = SessionStore.open(temporary.resolve("history"));
             RuntimeExecutionStore store = new RuntimeExecutionStore(database)) {
            RuntimeExecution execution;
            try (var session = sessions.create(new SessionStore.SessionCreateRequest(root, "test", "test", null, "react", "test"))) {
                execution = store.enqueue(root, session.sessionId(), "读取文件", ExecutionMode.REACT);
                execution = store.claimNext(root).orElseThrow();
                execution = store.commitRoutingDecision(execution.claim(), ExecutionMode.REACT, RoutingSource.EXPLICIT);
                new ExecutionFinalizer(store).ensureStarted(execution, session);
                session.append(new SessionEventDraft(SessionEvent.Types.EXECUTION_END, "react", "test", "test", false,
                        SessionEvent.SurfaceOperation.none(), new ObjectMapper().createObjectNode()
                        .put("executionId", execution.id()).put("selectedMode", "react").put("outcome", "succeeded").put("status", "completed")));
            }
            RuntimeExecution unrelated = store.enqueue(other, "unrelated", "读取文件", ExecutionMode.REACT);
            unrelated = store.claimNext(other).orElseThrow();
            // Simulate a migrated v1 RUNNING row without owner metadata.
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var update = connection.prepareStatement("UPDATE runtime_executions SET owner_id=NULL WHERE id=?")) {
                update.setString(1, execution.id()); update.executeUpdate();
            }
            try (var host = new RuntimeExecutionHost(store, RuntimeExecutionOptions.defaults(), ExecutionEventSink.discarding(), temporary.resolve("locks"))) {
                host.registerWorkspace(root, new DefaultWorkspaceRuntimeFactory(store, sessions, identity -> new NoCallClient()));
                assertEquals(ExecutionStatus.COMPLETED, store.find(execution.id()).orElseThrow().status());
                assertEquals(2, store.find(execution.id()).orElseThrow().attempt());
                assertEquals(ExecutionStatus.RUNNING, store.find(unrelated.id()).orElseThrow().status());
                assertEquals(unrelated.claim(), store.find(unrelated.id()).orElseThrow().claim());
            }
        }
    }
    private static final class NoCallClient implements LlmClient {
        public String getProviderName() { return "test"; }
        public String getModelName() { return "test"; }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) { throw new AssertionError("Recovery must not call the model"); }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
    }
}
