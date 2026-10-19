package com.codeagent.cli;

import com.codeagent.agent.Agent;
import com.codeagent.history.*;
import com.codeagent.llm.LlmClient;
import com.codeagent.runtime.execution.*;
import com.codeagent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;

class SessionResumeControllerTest {
    @TempDir Path temp;

    @Test void idleListAndCancelNeverCreateSessionAndFirstInputCreatesExactlyOne() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            assertNull(Main.openStartupSession(f.store, f.request, false));
            assertNull(f.active.get());
            var output = new ArrayList<String>();
            var ui = new TerminalSessionResumeUi(f.controller, output::add);
            ui.open(null);
            ui.accept("cancel");
            assertTrue(f.store.list(f.workspace, 20).isEmpty());
            assertTrue(f.controller.startupSummary().contains("0"));
            assertNull(f.active.get());
            var first = f.controller.ensureSession();
            assertSame(first, f.controller.ensureSession());
            assertEquals(1, f.store.list(f.workspace, 20).size());
            assertEquals(1, f.binds.get());
        }
    }

    @Test void pickerAndHistoryCancelKeepPointersAndNeverSubmitOrCallModel() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            String id = f.parent("old", 35);
            var previous = f.controller.ensureSession();
            var output = new ArrayList<String>();
            var ui = new TerminalSessionResumeUi(f.controller, output::add);
            ui.open(null);
            assertTrue(ui.pending());
            ui.accept("cancel");
            assertSame(previous, f.active.get());
            ui.open(id);
            assertEquals(id, f.active.get().sessionId());
            assertTrue(ui.pending());
            assertTrue(output.stream().anyMatch(s -> s.contains("old-0")));
            assertFalse(output.stream().anyMatch(s -> s.contains("old-34")));
            ui.accept("cancel");
            assertFalse(ui.pending());
            assertEquals(id, f.active.get().sessionId());
            ui.open(id);
            ui.accept("n");
            assertTrue(output.stream().anyMatch(s -> s.contains("old-34")));
            assertTrue(f.executions.list(f.workspace, 20).isEmpty());
        }
    }

    @Test void sourceTargetBusyAndPendingInteractionRejectWhileFailedBindingRollsBack() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            String target = f.parent("target", 1);
            var previous = f.controller.ensureSession();
            f.busy.add(target);
            assertThrows(IllegalStateException.class, () -> f.controller.attach(target));
            f.busy.clear(); f.busy.add(previous.sessionId());
            assertThrows(IllegalStateException.class, () -> f.controller.attach(target));
            f.busy.clear(); f.pending.add(target);
            assertThrows(IllegalStateException.class, () -> f.controller.attach(target));
            f.pending.clear(); f.failBinding.set(true);
            Agent previousAgent = f.agent.get();
            assertThrows(IllegalStateException.class, () -> f.controller.attach(target));
            assertSame(previous, f.active.get());
            assertSame(previousAgent, f.agent.get());
            assertSame(previousAgent.getMemoryManager().getContextProfile(), f.tools.getContextProfile());
            f.controller.attach(target);
            assertEquals(target, f.active.get().sessionId());
        }
    }

    @Test void mainWaitRegistrationFreezesQueuedOnlyParentAndSelectionNeverClaimsIt() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            String id;
            try (var session = f.store.create(f.request)) { id = session.sessionId(); }
            var old = f.executions.enqueue(f.workspace, id, "saved waiting request", null);
            var factory = new DefaultWorkspaceRuntimeFactory(f.executions, f.store, ignored -> new NoCallClient());
            try (var host = new RuntimeExecutionHost(f.executions, RuntimeExecutionOptions.defaults(),
                    ExecutionEventSink.discarding(), temp.resolve("locks"))) {
                Main.registerIdleWorkspace(host, f.workspace, factory);
                host.start();
                assertNull(f.active.get());
                assertEquals(1, f.store.list(f.workspace, 20).size());
                assertTrue(f.executions.find(old.id()).orElseThrow().awaitingInput());
                f.controller.attach(id);
                assertEquals(id, f.active.get().sessionId());
                var output = new ArrayList<String>();
                new TerminalSessionResumeUi(f.controller, output::add).open(id);
                assertTrue(output.stream().anyMatch(s -> s.contains("1") && s.contains("等待")));
                assertFalse(output.stream().anyMatch(s -> s.contains("saved waiting request")));
                assertEquals(0, f.executions.find(old.id()).orElseThrow().attempt());
                assertEquals(1, f.executions.list(f.workspace, 20).size());
            }
        }
    }

    @Test void clearRequiresOptInAndBothModesAndLongChunksRemainBrowsableWithoutWriting() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            String id;
            try (var session = f.store.create(f.request)) {
                id = session.sessionId();
                session.append(Fixture.message("react", "user", "older-cleared"));
                session.append(new SessionEventDraft(SessionEvent.Types.SURFACE_CLEAR, "react", "agent", "agent", false,
                        SessionEvent.SurfaceOperation.clear(), new ObjectMapper().createObjectNode()));
                session.append(Fixture.message("react", "user", "current-react"));
                session.append(new SessionEventDraft(SessionEvent.Types.TURN_START, "plan", "plan-agent", "plan-conversation",
                        false, SessionEvent.SurfaceOperation.none(), new ObjectMapper().createObjectNode()
                        .put("mode", "plan").put("turnId", "history-turn").put("planId", "history-plan")));
                session.append(Fixture.message("plan", "assistant", "文".repeat(4096 * 31) + "final-tail"));
            }
            var output = new ArrayList<String>();
            var ui = new TerminalSessionResumeUi(f.controller, output::add);
            ui.open(id);
            assertTrue(output.stream().anyMatch(s -> s.contains("current-react")));
            assertFalse(output.stream().anyMatch(s -> s.contains("older-cleared")));
            long sequence = f.active.get().projection().lastAppliedSequence();
            var messages = f.active.get().projection().messages();
            ui.accept("all");
            assertTrue(output.stream().anyMatch(s -> s.contains("older-cleared")));
            int pages = 0;
            while (ui.pending() && pages++ < 10) ui.accept("n");
            assertFalse(ui.pending());
            assertTrue(output.stream().anyMatch(s -> s.contains("PLAN/ASSISTANT") && s.contains("final-tail")));
            assertEquals(sequence, f.active.get().projection().lastAppliedSequence());
            assertEquals(messages, f.active.get().projection().messages());
            assertTrue(f.executions.list(f.workspace, 20).isEmpty());
        }
    }

    @Test void pickerPagesSearchAndUnavailableSelectionNeverCreateOrSwitchUntilConfirmed() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            for (int i = 0; i < 21; i++) f.parent("title-" + i, 1);
            String chosen = f.parent("needle", 1);
            var previous = f.controller.ensureSession();
            var output = new ArrayList<String>();
            var ui = new TerminalSessionResumeUi(f.controller, output::add);
            ui.open(null);
            ui.accept("n");
            assertTrue(output.stream().anyMatch(s -> s.contains("第 2 页")));
            ui.accept("p");
            ui.accept("? needle");
            assertSame(previous, f.active.get());
            ui.accept("1");
            assertEquals(chosen, f.active.get().sessionId());
            assertEquals(23, f.store.list(f.workspace, 100).size());
            assertTrue(f.executions.list(f.workspace, 20).isEmpty());
        }
    }

    static final class Fixture implements AutoCloseable {
        final Path workspace;
        final SessionStore store;
        final RuntimeExecutionStore executions;
        final SessionStore.SessionCreateRequest request;
        final AtomicReference<SessionStore.SessionHandle> active = new AtomicReference<>();
        final AtomicReference<Agent> agent;
        final SessionExecutionContextRegistry<SessionExecutionContext> registry;
        final SessionResumeController controller;
        final ToolRegistry tools = new ToolRegistry();
        final Set<String> busy = new HashSet<>(), pending = new HashSet<>();
        final AtomicInteger binds = new AtomicInteger();
        final AtomicBoolean failBinding = new AtomicBoolean();
        Fixture(Path temp) throws Exception {
            workspace = Files.createDirectory(temp.resolve("workspace"));
            store = SessionStore.open(temp.resolve("history"));
            executions = new RuntimeExecutionStore(temp.resolve("tasks.db"));
            request = new SessionStore.SessionCreateRequest(workspace, "test", "test", null, "react", "agent");
            agent = new AtomicReference<>(newAgent());
            var factory = new SessionExecutionContextFactory(store, workspace, ignored -> newAgent());
            registry = new SessionExecutionContextRegistry<>(factory::load, ignored -> true, pending::contains);
            var directory = new SessionResumeService(store, (id, path) -> {
                try { return new SessionResumeService.RecoverySummary((int) executions.listRecoveryState(path, id)
                        .stream().filter(RuntimeExecution::awaitingInput).count(), false); }
                catch (Exception e) { throw new java.io.IOException(e); }
            });
            controller = new SessionResumeController(store, workspace, directory, registry, new ReentrantLock(),
                    active, agent, id -> busy.contains(id) || executions.hasActiveOrReadyExecution(id),
                    pending::contains, id -> { binds.incrementAndGet(); if (failBinding.getAndSet(false)) {
                        try (var lease = registry.acquire(id)) { lease.context().activateSharedBindings(); }
                        throw new IllegalStateException("injected binding failure"); } }, () -> request);
        }
        Agent newAgent() {
            return new Agent(new NoCallClient(), tools); }
        String parent(String text, int count) throws Exception {
            try (var session = store.create(request)) {
                for (int i = 0; i < count; i++) {
                    session.append(message("react", "user", text + "-" + i));
                }
                return session.sessionId();
            }
        }
        static SessionEventDraft message(String mode, String role, String text) {
            var json = new ObjectMapper();
            var payload = json.createObjectNode();
            payload.putObject("conversation").put("mode", mode).put("role", role).put("content", text);
            if ("plan".equals(mode)) {
                payload.put("turnId", "history-turn");
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload.get("conversation"))
                        .put("turnId", "history-turn").put("planId", "history-plan");
            }
            payload.set("message", json.valueToTree("user".equals(role) ? LlmClient.Message.user(text) : LlmClient.Message.assistant(text)));
            return new SessionEventDraft("user".equals(role) ? SessionEvent.Types.USER_MESSAGE : SessionEvent.Types.ASSISTANT_MESSAGE,
                    mode, "react".equals(mode) ? "agent" : "plan-agent", "react".equals(mode) ? "agent" : "plan-conversation",
                    false, SessionEvent.SurfaceOperation.append(), payload);
        }
        public void close() throws Exception { registry.close(); executions.close(); store.close(); }
    }
    static final class NoCallClient implements LlmClient {
        public String getProviderName() { return "test"; }
        public String getModelName() { return "test"; }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) { throw new AssertionError("No model call from browsing"); }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
    }
}
