package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.history.SessionStore;
import com.codeagent.llm.LlmClient;
import com.codeagent.runtime.interaction.InteractionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeExecutionHostIntegrationTest {
    @TempDir Path temporary;

    @Test void actualAgentsWriteSeparateWorkspacesConcurrentlyAndPreserveSessionFifo() throws Exception {
        Path a = Files.createDirectory(temporary.resolve("a"));
        Path b = Files.createDirectory(temporary.resolve("b"));
        CountDownLatch both = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<RuntimeExecutionHost> reference = new AtomicReference<>();
        List<RuntimeExecutionEvent> events = new CopyOnWriteArrayList<>();
        List<String> requests = new CopyOnWriteArrayList<>();
        ExecutionEventSink sink = new ExecutionEventSink() {
            public boolean supportsInteractions() { return true; }
            public void accept(RuntimeExecutionEvent event) {
                events.add(event);
                if (event.payload() instanceof InteractionRequest request) {
                    try { assertTrue(reference.get().respond(event.workspaceKey(), event.executionId(), request.interactionId(), "approve", "")); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                }
            }
        };
        try (SessionStore sessions = SessionStore.open(temporary.resolve("history"));
             RuntimeExecutionStore store = new RuntimeExecutionStore(temporary.resolve("execution.db"));
             RuntimeExecutionHost host = new RuntimeExecutionHost(store, new RuntimeExecutionOptions(2), sink, temporary.resolve("locks"))) {
            reference.set(host);
            DefaultWorkspaceRuntimeFactory factory = new DefaultWorkspaceRuntimeFactory(store, sessions,
                    identity -> new WritingClient(identity.canonicalRoot().getFileName().toString(), both, release, requests));
            var identityA = host.registerWorkspace(a, factory);
            var identityB = host.registerWorkspace(b, factory);
            String sa = host.createSession(identityA.key());
            String anotherSessionA = host.createSession(identityA.key());
            String sb = host.createSession(identityB.key());
            RuntimeExecution ea = host.submit(sa, "写入 result.txt，内容为项目名称", ExecutionMode.REACT);
            RuntimeExecution second = host.submit(sa, "读取 result.txt", ExecutionMode.REACT);
            RuntimeExecution sameWorkspace = host.submit(anotherSessionA, "读取 result.txt", ExecutionMode.REACT);
            RuntimeExecution eb = host.submit(sb, "写入 result.txt，内容为项目名称", ExecutionMode.REACT);
            host.start();
            try {
                assertTrue(both.await(10, TimeUnit.SECONDS), () -> "Model requests=" + requests
                        + "; scheduler=" + host.scheduler().lastFailure(identityA.key()) + "/" + host.scheduler().lastFailure(identityB.key()));
                assertEquals(ExecutionStatus.RUNNING, store.find(ea.id()).orElseThrow().status());
                assertEquals(ExecutionStatus.RUNNING, store.find(eb.id()).orElseThrow().status());
                assertEquals(ExecutionStatus.ENQUEUED, store.find(second.id()).orElseThrow().status());
                assertEquals(ExecutionStatus.ENQUEUED, store.find(sameWorkspace.id()).orElseThrow().status());
            } finally { release.countDown(); }
            awaitTerminal(store, ea.id()); awaitTerminal(store, eb.id()); awaitTerminal(store, second.id());
            awaitTerminal(store, sameWorkspace.id());
            assertEquals("a", Files.readString(a.resolve("result.txt")));
            assertEquals("b", Files.readString(b.resolve("result.txt")));
            assertTrue(events.stream().anyMatch(e -> e.workspaceKey().equals(identityA.key()) && e.executionId().equals(ea.id()) && e.kind().equals("completed")));
            assertTrue(events.stream().anyMatch(e -> e.workspaceKey().equals(identityB.key()) && e.executionId().equals(eb.id()) && e.kind().equals("completed")));
            assertThrows(Exception.class, () -> host.attachSession(identityB.key(), sa));
        }
    }

    @Test void missingInteractionAdapterRejectsWriteWithoutSideEffects() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("no-adapter"));
        try (SessionStore sessions = SessionStore.open(temporary.resolve("history"));
             RuntimeExecutionStore store = new RuntimeExecutionStore(temporary.resolve("execution.db"));
             RuntimeExecutionHost host = new RuntimeExecutionHost(store, new RuntimeExecutionOptions(2),
                     ExecutionEventSink.discarding(), temporary.resolve("locks"))) {
            var factory = new DefaultWorkspaceRuntimeFactory(store, sessions,
                    identity -> new WritingClient("blocked", new CountDownLatch(0), new CountDownLatch(0),
                            new CopyOnWriteArrayList<>()));
            var identity = host.registerWorkspace(root, factory);
            var execution = host.submit(host.createSession(identity.key()), "写入 result.txt", ExecutionMode.REACT);
            host.start();
            awaitTerminal(store, execution.id());
            assertFalse(Files.exists(root.resolve("result.txt")));
        }
    }

    @Test void capacityTwoKeepsThirdWorkspaceQueuedUntilOneFinishes() throws Exception {
        CountDownLatch entered = new CountDownLatch(2); CountDownLatch release = new CountDownLatch(1);
        try (SessionStore sessions = SessionStore.open(temporary.resolve("history"));
             RuntimeExecutionStore store = new RuntimeExecutionStore(temporary.resolve("execution.db"));
             RuntimeExecutionHost host = new RuntimeExecutionHost(store, new RuntimeExecutionOptions(2),
                     ExecutionEventSink.discarding(), temporary.resolve("locks"))) {
            var factory = new DefaultWorkspaceRuntimeFactory(store, sessions,
                    identity -> new WritingClient(identity.key().value(), entered, release, new CopyOnWriteArrayList<>()));
            List<RuntimeExecution> executions = new ArrayList<>();
            for (String name : List.of("a", "b", "c")) {
                var identity = host.registerWorkspace(Files.createDirectory(temporary.resolve(name)), factory);
                executions.add(host.submit(host.createSession(identity.key()), "写入 result.txt", ExecutionMode.REACT));
            }
            host.start();
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                int running = 0; int queued = 0;
                for (var execution : executions) {
                    var status = store.find(execution.id()).orElseThrow().status();
                    if (status == ExecutionStatus.RUNNING) running++;
                    if (status == ExecutionStatus.ENQUEUED) queued++;
                }
                assertEquals(2, running); assertEquals(1, queued);
            } finally { release.countDown(); }
            for (var execution : executions) awaitTerminal(store, execution.id());
        }
    }

    @Test void headlessPlanDoesNotApproveItsOwnHumanGate() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        try (var sessions = SessionStore.open(temporary.resolve("history"));
             var store = new RuntimeExecutionStore(temporary.resolve("executions.db"));
             var host = new RuntimeExecutionHost(store, RuntimeExecutionOptions.defaults(), ExecutionEventSink.discarding(), temporary.resolve("locks"))) {
            LlmClient client = new LlmClient() {
                public String getProviderName() { return "test"; }
                public String getModelName() { return "test"; }
                public ChatResponse chat(List<Message> messages, List<Tool> tools) { return new ChatResponse("assistant", "[]", List.of(), 1, 1); }
                public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
            };
            var identity = host.registerWorkspace(root, new DefaultWorkspaceRuntimeFactory(store, sessions, ignored -> client,
                    () -> new com.codeagent.plan.PlanStateStore(temporary.resolve("plans.db"))));
            RuntimeExecution execution = host.submit(host.createSession(identity.key()), "列出当前目录的文件", ExecutionMode.PLAN);
            host.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!store.find(execution.id()).orElseThrow().terminal() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(ExecutionOutcome.CANCELED, store.find(execution.id()).orElseThrow().outcome());
            assertTrue(host.pending(identity.key()).isEmpty());
        }
    }

    @Test void realGitWorktreesWaitForTheirSharedMetadataLease() throws Exception {
        Path a = Files.createDirectory(temporary.resolve("a"));
        Path b = temporary.resolve("b");
        git(a, "init");
        git(a, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "commit", "--allow-empty", "-m", "fixture");
        git(a, "worktree", "add", "--detach", b.toString());
        Path canonicalA = a.toRealPath();
        assertEquals(DefaultWorkspaceRuntimeFactory.sharedGitResources(a), DefaultWorkspaceRuntimeFactory.sharedGitResources(b));
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var sessions = SessionStore.open(temporary.resolve("history"));
             var store = new RuntimeExecutionStore(temporary.resolve("executions.db"));
             var host = new RuntimeExecutionHost(store, new RuntimeExecutionOptions(2), ExecutionEventSink.discarding(), temporary.resolve("locks"))) {
            var factory = new DefaultWorkspaceRuntimeFactory(store, sessions, identity -> {
                boolean isA = identity.canonicalRoot().equals(canonicalA);
                return new WritingClient(isA ? "a" : "b", isA ? first : second,
                        isA ? release : new CountDownLatch(0), new CopyOnWriteArrayList<>());
            });
            var wa = host.registerWorkspace(a, factory);
            var wb = host.registerWorkspace(b, factory);
            var ea = host.submit(host.createSession(wa.key()), "写入 result.txt", ExecutionMode.REACT);
            host.start();
            try {
                assertTrue(first.await(10, TimeUnit.SECONDS));
                var eb = host.submit(host.createSession(wb.key()), "写入 result.txt", ExecutionMode.REACT);
                assertFalse(second.await(200, TimeUnit.MILLISECONDS));
                assertEquals(ExecutionStatus.ENQUEUED, store.find(eb.id()).orElseThrow().status());
                release.countDown();
                assertTrue(second.await(10, TimeUnit.SECONDS));
                awaitTerminal(store, ea.id());
                awaitTerminal(store, eb.id());
            } finally { release.countDown(); }
        }
    }

    private static void git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Fixture git command timed out");
            assertEquals(0, process.exitValue(), () -> "Fixture git command failed: " + command);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    static RuntimeExecution awaitTerminal(RuntimeExecutionStore store, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        RuntimeExecution current;
        do {
            current = store.find(id).orElseThrow();
            if (current.terminal()) {
                assertEquals(ExecutionStatus.COMPLETED, current.status(), "Unexpected terminal error: " + current.error());
                return current;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Execution did not finish: " + current.status());
    }

    private static final class WritingClient implements LlmClient {
        final String content;
        final CountDownLatch both;
        final CountDownLatch release;
        int calls;
        final List<String> requests;
        WritingClient(String content, CountDownLatch both, CountDownLatch release, List<String> requests) {
            this.content = content; this.both = both; this.release = release;
            this.requests = requests;
        }
        public String getProviderName() { return "test"; }
        public String getModelName() { return "test"; }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws java.io.IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws java.io.IOException {
            requests.add(content + ":tools=" + (tools == null ? "null" : tools.size()));
            if (tools == null || tools.isEmpty()) return new ChatResponse("assistant", "[]", List.of(), 10, 10);
            if (calls++ == 0) {
                both.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("latch timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                return new ChatResponse("assistant", "", List.of(new ToolCall("write", new ToolCall.Function("write_file",
                        "{\"path\":\"result.txt\",\"content\":\"" + content + "\"}"))), 10, 10);
            }
            listener.onContentDelta("done");
            return new ChatResponse("assistant", "done", List.of(), 10, 10);
        }
    }
}
