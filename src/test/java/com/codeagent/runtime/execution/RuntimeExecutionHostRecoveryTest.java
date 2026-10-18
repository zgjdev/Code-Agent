package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.RoutingSource;
import com.codeagent.history.*;
import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeExecutionHostRecoveryTest {
    @TempDir Path temporary;
    @Test void cancelHeldOriginalEndsItsEnvelopeWithoutReleasingOtherHeldWork() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("cancel-workspace"));
        try(var sessions=SessionStore.open(temporary.resolve("cancel-history"));
            var store=new RuntimeExecutionStore(temporary.resolve("cancel.db"))) {
            RuntimeExecution original;String sessionId;
            try(var session=sessions.create(new SessionStore.SessionCreateRequest(root,"test","test",null,"plan","agent"))) {
                sessionId=session.sessionId();store.enqueue(root,sessionId,"original Plan",ExecutionMode.PLAN);
                original=store.claimNext(root,"old-owner").orElseThrow();
                original=store.commitRoutingDecision(original.claim(),ExecutionMode.PLAN,RoutingSource.EXPLICIT);
                new ExecutionFinalizer(store).ensureStarted(original,session);
            }
            var other=store.enqueue(root,sessionId,"other held work",ExecutionMode.REACT);
            try(var host=new RuntimeExecutionHost(store,RuntimeExecutionOptions.defaults(),ExecutionEventSink.discarding(),temporary.resolve("cancel-locks"))) {
                host.registerWorkspace(root,new DefaultWorkspaceRuntimeFactory(store,sessions,id->new NoCallClient()),RecoveryPolicy.WAIT_FOR_USER);
                host.cancel(original.id());
                assertEquals(ExecutionStatus.CANCELED,store.find(original.id()).orElseThrow().status());
                String originalId=original.id();
                assertTrue(sessions.readParentSnapshot(sessionId,root,-1).events().stream().anyMatch(e->SessionEvent.Types.EXECUTION_END.equals(e.type())
                        &&originalId.equals(e.payload().path("executionId").asText())));
                assertTrue(store.find(other.id()).orElseThrow().awaitingInput());
            }
        }
    }
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
    @Test void reconcilesEndedRequeuedAttemptBeforeApplyingUserGate() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("workspace"));
        try(var sessions=SessionStore.open(temporary.resolve("history"));
            var store=new RuntimeExecutionStore(temporary.resolve("execution.db"))) {
            RuntimeExecution task;
            try(var session=sessions.create(new SessionStore.SessionCreateRequest(root,"test","test",null,"react","test"))) {
                task=store.enqueue(root,session.sessionId(),"old",ExecutionMode.REACT);
                task=store.claimNext(root,"old-owner").orElseThrow();
                task=store.commitRoutingDecision(task.claim(),ExecutionMode.REACT,RoutingSource.EXPLICIT);
                new ExecutionFinalizer(store).ensureStarted(task,session);
                session.append(new SessionEventDraft(SessionEvent.Types.EXECUTION_END,"react","test","test",false,
                    SessionEvent.SurfaceOperation.none(),new ObjectMapper().createObjectNode()
                    .put("executionId",task.id()).put("selectedMode","react").put("outcome","succeeded").put("status","completed")));
                store.interruptForRecovery(task.claim(),"runtime_shutdown");
            }
            try(var host=new RuntimeExecutionHost(store,RuntimeExecutionOptions.defaults(),ExecutionEventSink.discarding(),temporary.resolve("locks"))) {
                host.registerWorkspace(root,new DefaultWorkspaceRuntimeFactory(store,sessions,identity->new NoCallClient()),RecoveryPolicy.WAIT_FOR_USER);
                assertEquals(ExecutionStatus.COMPLETED,store.find(task.id()).orElseThrow().status());
                assertFalse(store.find(task.id()).orElseThrow().awaitingInput());
            }
        }
    }

    @Test void waitPolicyHoldsOldInputAndAutomaticCannotClearGate() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace"));
        try (var sessions = SessionStore.open(temporary.resolve("history"));
             var store = new RuntimeExecutionStore(temporary.resolve("execution.db"))) {
            String sessionId;
            try (var session = sessions.create(new SessionStore.SessionCreateRequest(root, "test", "test", null, "react", "test"))) {
                sessionId=session.sessionId();
            }
            var held=store.enqueue(root,sessionId,"old",null);
            var factory=new DefaultWorkspaceRuntimeFactory(store,sessions,identity->new NoCallClient());
            try(var host=new RuntimeExecutionHost(store,RuntimeExecutionOptions.defaults(),ExecutionEventSink.discarding(),temporary.resolve("locks"))) {
                host.registerWorkspace(root,factory,RecoveryPolicy.WAIT_FOR_USER);
                host.start();
                assertTrue(store.find(held.id()).orElseThrow().awaitingInput());
                assertFalse(store.hasActiveOrReadyExecution(sessionId));
            }
            try(var host=new RuntimeExecutionHost(store,RuntimeExecutionOptions.defaults(),ExecutionEventSink.discarding(),temporary.resolve("locks"))) {
                host.registerWorkspace(root,factory);
                host.start();
                assertTrue(store.find(held.id()).orElseThrow().awaitingInput());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,false",
                "false,false,true", "false,true,true", "true,false,true", "true,true,true"})
    void reconcilesEndedQueuedAttemptWithAnotherSessionsStaleRunning(
            boolean runningCreatedFirst, boolean runningEnded, boolean cancelRunning) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace"));
        Path otherRoot = Files.createDirectory(temporary.resolve("other"));
        try (var sessions = SessionStore.open(temporary.resolve("history"));
             var store = new RuntimeExecutionStore(temporary.resolve("execution.db"))) {
            RuntimeExecution queued;
            RuntimeExecution running;
            try (var queuedSession = sessions.create(new SessionStore.SessionCreateRequest(root, "test", "test", null, "react", "test"));
                 var runningSession = sessions.create(new SessionStore.SessionCreateRequest(root, "test", "test", null, "plan", "test"));
                 var previousOwner = WorkspaceOwnerLease.acquire(temporary.resolve("locks"), new WorkspaceIdentityResolver().resolve(root), "previous")) {
                if (runningCreatedFirst) {
                    running = store.enqueue(root, runningSession.sessionId(), "old running", ExecutionMode.PLAN);
                    queued = store.enqueue(root, queuedSession.sessionId(), "old queued", ExecutionMode.REACT);
                } else {
                    queued = store.enqueue(root, queuedSession.sessionId(), "old queued", ExecutionMode.REACT);
                    running = store.enqueue(root, runningSession.sessionId(), "old running", ExecutionMode.PLAN);
                }
                assertEquals(runningCreatedFirst ? running.id() : queued.id(),
                        store.workspaceExecutions(previousOwner.identity().key()).get(0).id());
                queued = store.claimCandidate(previousOwner, queued.id()).orElseThrow();
                queued = store.commitRoutingDecision(queued.claim(), ExecutionMode.REACT, RoutingSource.EXPLICIT);
                new ExecutionFinalizer(store).ensureStarted(queued, queuedSession);
                appendSuccessfulEnd(queuedSession, queued, "react");
                store.interruptForRecovery(queued.claim(), "runtime_shutdown");
                running = store.claimCandidate(previousOwner, running.id()).orElseThrow();
                running = store.commitRoutingDecision(running.claim(), ExecutionMode.PLAN, RoutingSource.EXPLICIT);
                new ExecutionFinalizer(store).ensureStarted(running, runningSession);
                if (runningEnded) appendSuccessfulEnd(runningSession, running, "plan");
                if (cancelRunning) store.requestCancel(running.id());
            }
            var queuedClaim = queued.claim();
            var runningClaim = running.claim();
            var unrelated = store.enqueue(otherRoot, "unrelated", "other workspace", ExecutionMode.REACT);
            unrelated = store.claimNext(otherRoot, "other-owner").orElseThrow();
            try (var host = new RuntimeExecutionHost(store, RuntimeExecutionOptions.defaults(), ExecutionEventSink.discarding(), temporary.resolve("locks"))) {
                var factory = new DefaultWorkspaceRuntimeFactory(store, sessions, identity -> new NoCallClient());
                assertDoesNotThrow(() -> host.registerWorkspace(root, factory, RecoveryPolicy.WAIT_FOR_USER),
                        "END reconciliation must not take the workspace running slot");
                host.start();
                var reconciled = store.find(queued.id()).orElseThrow();
                assertEquals(ExecutionStatus.COMPLETED, reconciled.status());
                assertEquals(ExecutionOutcome.SUCCEEDED, reconciled.outcome());
                assertEquals(queued.attempt() + 1, reconciled.attempt(), "Reconciliation fences the old attempt without rerunning it");
                assertFalse(reconciled.awaitingInput());
                assertEquals(ExecutionMode.REACT, reconciled.selectedMode());
                var recoveredRunning = store.find(running.id()).orElseThrow();
                if (runningEnded) {
                    assertEquals(ExecutionStatus.COMPLETED, recoveredRunning.status(), "Durable END remains authoritative over later cancellation");
                    assertEquals(ExecutionOutcome.SUCCEEDED, recoveredRunning.outcome());
                } else if (cancelRunning) {
                    assertEquals(ExecutionStatus.CANCELED, recoveredRunning.status(), "Pending cancellation wins before freezing");
                } else {
                    assertTrue(recoveredRunning.awaitingInput());
                    assertEquals(ExecutionStatus.ENQUEUED, recoveredRunning.status());
                    assertNull(recoveredRunning.ownerId());
                }
                assertEquals(ExecutionMode.PLAN, recoveredRunning.selectedMode());
                assertThrows(IllegalStateException.class, () -> store.complete(queuedClaim, ExecutionOutcome.SUCCEEDED, "late", null));
                if (!cancelRunning || runningEnded)
                    assertThrows(IllegalStateException.class, () -> store.complete(runningClaim, ExecutionOutcome.SUCCEEDED, "late", null));
                assertEquals(unrelated, store.find(unrelated.id()).orElseThrow());
                assertTrue(store.running(root).isEmpty());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"ordinal", "explicitMode", "selectedMode", "status", "outcome"})
    void refusesConflictingEndEnvelopeWithoutChangingExecution(String conflict) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace"));
        try (var sessions = SessionStore.open(temporary.resolve("history"));
             var store = new RuntimeExecutionStore(temporary.resolve("execution.db"))) {
            RuntimeExecution execution;
            try (var session = sessions.create(new SessionStore.SessionCreateRequest(root, "test", "test", null, "react", "test"))) {
                store.enqueue(root, session.sessionId(), "old", ExecutionMode.REACT);
                execution = store.claimNext(root, "previous").orElseThrow();
                execution = store.commitRoutingDecision(execution.claim(), ExecutionMode.REACT, RoutingSource.EXPLICIT);
                session.append(new SessionEventDraft(SessionEvent.Types.EXECUTION_START, "react", "test", "test", false,
                        SessionEvent.SurfaceOperation.none(), new ObjectMapper().createObjectNode()
                        .put("executionId", execution.id()).put("ordinal", execution.ordinal() + (conflict.equals("ordinal") ? 1 : 0))
                        .put("explicitMode", conflict.equals("explicitMode") ? "plan" : "react")));
                session.append(new SessionEventDraft(SessionEvent.Types.EXECUTION_END, "react", "test", "test", false,
                        SessionEvent.SurfaceOperation.none(), new ObjectMapper().createObjectNode()
                        .put("executionId", execution.id()).put("selectedMode", conflict.equals("selectedMode") ? "plan" : "react")
                        .put("outcome", conflict.equals("outcome") ? "unknown" : "succeeded")
                        .put("status", conflict.equals("status") ? "failed" : "completed")));
            }
            try (var host = new RuntimeExecutionHost(store, RuntimeExecutionOptions.defaults(), ExecutionEventSink.discarding(), temporary.resolve("locks"))) {
                assertThrows(IllegalArgumentException.class, () -> host.registerWorkspace(root,
                        new DefaultWorkspaceRuntimeFactory(store, sessions, identity -> new NoCallClient()), RecoveryPolicy.WAIT_FOR_USER));
                assertEquals(execution, store.find(execution.id()).orElseThrow(), "Validation failure must roll back the entire reconciliation");
            }
        }
    }

    private static void appendSuccessfulEnd(SessionStore.SessionHandle session, RuntimeExecution execution, String mode) throws Exception {
        session.append(new SessionEventDraft(SessionEvent.Types.EXECUTION_END, mode, "test", "test", false,
                SessionEvent.SurfaceOperation.none(), new ObjectMapper().createObjectNode()
                .put("executionId", execution.id()).put("selectedMode", mode).put("outcome", "succeeded").put("status", "completed")));
    }

    private static final class NoCallClient implements LlmClient {
        public String getProviderName() { return "test"; }
        public String getModelName() { return "test"; }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) { throw new AssertionError("Recovery must not call the model"); }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
    }
}
