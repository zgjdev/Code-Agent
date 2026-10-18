package com.codeagent.runtime.execution;

import com.codeagent.agent.Agent;
import com.codeagent.agent.ExecutionMode;
import com.codeagent.history.*;
import com.codeagent.llm.LlmClient;
import com.codeagent.model.recovery.*;
import com.codeagent.runtime.interaction.InteractionBroker;
import com.codeagent.skill.SkillContextBuffer;
import com.codeagent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;

class ContinuationAdmissionServiceTest {
    @TempDir Path temp;

    @Test void receiptEndsBeforeReleaseAndResumedInvocationKeepsBothIdentities() throws Exception {
        try (var f = new Fixture()) {
            var target = f.store.enqueue(temp, f.session.sessionId(), "original invoice goal", ExecutionMode.PLAN);
            f.store.freezeForUser(f.owner);
            var request = f.store.enqueue(temp, f.session.sessionId(), "continue but only inspect", null);
            request = f.store.claimCandidate(f.owner, request.id()).orElseThrow();
            var scope = f.capability.requireRunningScope(request.claim(), f.context);
            var intent = new ContinuationIntent(ContinuationIntent.Kind.CONTINUE, RequestSemantics.CONTINUE, target.id(), 1);
            var prepared = f.capability.prepare(scope, target.id(), 1);
            assertEquals(ContinuationReleaseResult.NOT_READY, f.capability.releaseCompleted(scope));
            // E1 Plan turn remains open while the separately owned E2 receipt ends.
            var json = new ObjectMapper().createObjectNode().put("mode", "plan").put("turnId", "turn").put("planId", "plan");
            f.session.append(new SessionEventDraft(SessionEvent.Types.TURN_START, "plan", "plan-agent", "plan-conversation", false, SessionEvent.SurfaceOperation.none(), json));
            var receipt = f.service.accept(scope, intent, LlmClient.Message.user(request.submittedInput()));
            assertEquals(ContinuationReleaseResult.RELEASED, receipt.releaseResult());
            assertEquals(ExecutionStatus.COMPLETED, receipt.request().status());
            assertTrue(f.session.projection().executionEnvelopes().get(request.id()).ended());
            assertTrue(f.session.projection().openTurns().containsKey("turn"));
            assertEquals(ContinuationReleaseResult.ALREADY_RELEASED, f.service.accept(scope, intent, LlmClient.Message.user(request.submittedInput())).releaseResult());
            String requestId = request.id();
            assertEquals(1, f.session.readAll().stream().filter(e -> SessionEvent.Types.USER_MESSAGE.equals(e.type()) && requestId.equals(e.payload().path("executionId").asText())).count());
            var resumed = f.store.claimCandidate(f.owner, target.id()).orElseThrow();
            var resumedScope = f.capability.requireRunningScope(resumed.claim(), f.context);
            var invocation = f.service.continuationInvocation(resumedScope, f.snapshot());
            assertEquals(target.id(), invocation.execution().id());
            assertEquals(requestId, invocation.inputExecution().id());
            assertEquals("continue but only inspect", invocation.currentInput());
            assertEquals("original invoice goal", invocation.recoveryContext().originalSubmittedInput());
            assertEquals(ExecutionMode.PLAN, invocation.execution().explicitMode());
        }
    }

    @Test void statusUsesActualSourceSnapshotAndToolFreeAgentRequest() throws Exception {
        try (var f = new Fixture()) {
            var old = f.store.enqueue(temp, f.session.sessionId(), "original goal", null);
            f.store.freezeForUser(f.owner);
            var request = f.store.enqueue(temp, f.session.sessionId(), "what is the progress?", null);
            request = f.store.claimCandidate(f.owner, request.id()).orElseThrow();
            f.store.commitRequestSemantics(request.claim(), RequestSemantics.STATUS_QUESTION);
            var scope = f.capability.requireRunningScope(request.claim(), f.context);
            long user = f.service.persistStatusInput(scope, LlmClient.Message.user(request.submittedInput()));
            assertEquals(user, f.service.persistStatusInput(scope, LlmClient.Message.user(request.submittedInput())));
            var recovery = f.service.statusContext(scope, f.snapshot());
            try (var identity = com.codeagent.runtime.interaction.ExecutionInteractionContext.install(request.id(), request.sessionId())) {
                f.context.agent().runExecution(request.submittedInput(), request.submittedInput(), recovery);
            }
            assertTrue(f.client.tools.isEmpty());
            assertTrue(f.client.messages.stream().anyMatch(m -> m.content() != null && m.content().contains("runtime_recovery_context") && m.content().contains(old.id())));
            assertTrue(f.store.find(old.id()).orElseThrow().awaitingInput());
            assertThrows(RecoveryPreparationDeferredException.class, () -> f.service.statusContext(scope, f.snapshot()));
        }
    }

    @Test void staleTargetCannotAppendReceiptOrRelease() throws Exception {
        try (var f = new Fixture()) {
            var target = f.store.enqueue(temp, f.session.sessionId(), "original", null);
            f.store.freezeForUser(f.owner);
            var request = f.store.enqueue(temp, f.session.sessionId(), "continue", null);
            request = f.store.claimCandidate(f.owner, request.id()).orElseThrow();
            var scope = f.capability.requireRunningScope(request.claim(), f.context);
            long before = f.session.projection().lastAppliedSequence();
            assertThrows(IllegalStateException.class, () -> f.service.accept(scope,
                    new ContinuationIntent(ContinuationIntent.Kind.CONTINUE, RequestSemantics.CONTINUE, target.id(), 0), LlmClient.Message.user("continue")));
            assertEquals(before, f.session.projection().lastAppliedSequence());
            assertTrue(f.store.find(target.id()).orElseThrow().awaitingInput());
        }
    }

    private final class Fixture implements AutoCloseable {
        final RuntimeExecutionStore store = new RuntimeExecutionStore(temp.resolve("runtime.db"));
        final SessionStore sessions = SessionStore.open(temp.resolve("history"));
        final SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(temp, "test", "test", null, "react", "test"));
        final WorkspaceIdentity identity = new WorkspaceIdentityResolver().resolve(temp);
        final WorkspaceOwnerLease owner = WorkspaceOwnerLease.acquire(temp.resolve("locks"), identity, "owner");
        final Client client = new Client();
        final ToolRegistry tools = new ToolRegistry();
        final SessionExecutionContext context;
        final SessionExecutionContextRegistry<SessionExecutionContext> registry;
        final WorkspaceRuntime runtime;
        final SessionExecutionContextRegistry.Lease<SessionExecutionContext> lease;
        final ContinuationCapability capability;
        final ContinuationAdmissionService service;
        Fixture() throws Exception {
            var agent = new Agent(client, tools); agent.attachSession(session);
            context = new SessionExecutionContext(agent, session, new SkillContextBuffer());
            registry = new SessionExecutionContextRegistry<>(id -> context, id -> true, id -> false);
            registry.adopt(session.sessionId(), context);
            runtime = new WorkspaceRuntime(identity, registry, new InteractionBroker(), new ReentrantLock(), Set.of(), () -> true,
                    new WorkspaceRuntime.SessionController() { public String create() { return session.sessionId(); } public void attach(String id) {} },
                    (e,t,c) -> { throw new AssertionError(); }, () -> {});
            capability = new ContinuationCapability(store, owner, runtime, () -> {});
            runtime.bindContinuationCapability(capability);
            runtime.mutationGate().lock(); lease = registry.acquire(session.sessionId());
            service = new ContinuationAdmissionService(store, capability);
        }
        RecoveryContextSnapshot snapshot() throws Exception {
            return new RecoveryContextSnapshot(identity.key().value(), session.sessionId(), 1, session.projection().lastAppliedSequence(), "a".repeat(64),
                    store.listRecoveryState(temp, session.sessionId()).stream().map(e -> new RecoveryExecutionState(e.id(), "REACT", "REACT", e.status().name(), e.dispatchState(), e.recoveryGeneration(), false, e.awaitingInput(), e.submittedInput())).toList(), List.of(), List.of());
        }
        public void close() throws Exception {
            lease.close(); runtime.mutationGate().unlock(); runtime.close(); owner.close(); sessions.close(); store.close(); tools.closeWorkspaceResources();
        }
    }
    private static final class Client implements LlmClient {
        List<Tool> tools = List.of(); List<Message> messages = List.of();
        public String getProviderName() { return "test"; } public String getModelName() { return "test"; }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            this.messages = messages; this.tools = tools == null ? List.of() : tools;
            return new ChatResponse("assistant", "The original goal is waiting for explicit continuation.", null, null, 1, 1, 0);
        }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
    }
}
