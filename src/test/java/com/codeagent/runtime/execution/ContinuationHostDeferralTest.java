package com.codeagent.runtime.execution;

import com.codeagent.agent.Agent;
import com.codeagent.history.SessionStore;
import com.codeagent.llm.LlmClient;
import com.codeagent.runtime.interaction.InteractionBroker;
import com.codeagent.skill.SkillContextBuffer;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;

class ContinuationHostDeferralTest {
    @TempDir Path temp;
    @Test void realHostDefersPreparationWithoutEndOrAutomaticRedispatch() throws Exception {
        CountDownLatch drained = new CountDownLatch(1);
        AtomicReference<WorkspaceRuntime> runtime = new AtomicReference<>();
        var tools = new ToolRegistry();
        try (var sessions = SessionStore.open(temp.resolve("history"));
             var store = new RuntimeExecutionStore(temp.resolve("runtime.db"));
             var host = new RuntimeExecutionHost(store, new RuntimeExecutionOptions(1), ExecutionEventSink.discarding(), temp.resolve("locks"))) {
            var identity = host.registerWorkspace(temp, (workspace, sink) -> {
                var factory = new SessionExecutionContextFactory(sessions, temp, buffer -> new Agent(new NoCall(), tools));
                var registry = new SessionExecutionContextRegistry<SessionExecutionContext>(factory::load, id -> true, id -> false);
                var controller = new WorkspaceRuntime.SessionController() {
                    public String create() throws Exception {
                        try (var handle = sessions.create(new SessionStore.SessionCreateRequest(temp, "test", "test", null, "react", "test"))) { return handle.sessionId(); }
                    }
                    public void attach(String id) throws Exception { try (var lease = registry.acquire(id)) {} }
                };
                var value = new WorkspaceRuntime(workspace, registry, new InteractionBroker(), new ReentrantLock(), Set.of(), () -> true, controller,
                        (execution, token, context) -> {
                            assertNotNull(runtime.get().continuationCapability().requireRunningScope(execution.claim(), context));
                            new ExecutionFinalizer(store).ensureStarted(execution, context.session());
                            throw new RecoveryPreparationDeferredException("recovery context budget unavailable");
                        }, () -> {});
                value.setAfterExecution(drained::countDown); runtime.set(value); return value;
            }, RecoveryPolicy.WAIT_FOR_USER);
            var request = host.submit(host.createSession(identity.key()), "continue", null);
            host.start();
            assertTrue(drained.await(10, TimeUnit.SECONDS));
            var held = store.find(request.id()).orElseThrow();
            assertTrue(held.awaitingInput());
            assertEquals(1, held.recoveryGeneration());
            assertEquals(1, held.attempt());
            assertTrue(store.nextQueuedExecution(identity.key()).isEmpty());
            try (var lease = runtime.get().contexts().acquire(request.sessionId())) {
                assertFalse(lease.context().session().projection().executionEnvelopes().get(request.id()).ended());
            }
            assertFalse(host.scheduler().isQuarantined(identity.key()));
        } finally { tools.closeWorkspaceResources(); }
    }
    private static final class NoCall implements LlmClient {
        public String getProviderName() { return "test"; } public String getModelName() { return "test"; }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) { throw new AssertionError("No model during preparation"); }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
    }
}
