package com.codeagent.agent;

import com.codeagent.context.ContextTokenTracker;
import com.codeagent.context.MeasuredUsage;
import com.codeagent.history.SessionEvent;
import com.codeagent.history.SessionStore;
import com.codeagent.llm.LlmClient;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionResumeTest {
    @TempDir
    Path tempDir;

    @Test
    void attachesOneSystemMessageAndRestoresWithoutDuplicatingIt() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        String sessionId;
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(request(workspace))) {
            sessionId = handle.sessionId();
            Agent agent = agent(new RecordingClient(List.of()), workspace);
            agent.attachSession(handle);
            assertEquals(List.of("system"), handle.projection().messages().stream()
                    .map(LlmClient.Message::role).toList());
        }

        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle resumed = store.resumeWritable(sessionId, workspace)) {
            Agent restored = agent(new RecordingClient(List.of()), workspace);
            restored.attachSession(resumed);
            assertEquals(1, restored.getConversationHistory().size());
            assertEquals(1, resumed.projection().messages().size());
            assertEquals(ContextTokenTracker.Mode.FULL_ESTIMATE,
                    restored.currentContextPrediction().mode());
        }
    }

    @Test
    void persistsCommittedToolConversation() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call-1", new LlmClient.ToolCall.Function("read_file", "{}"));
        RecordingClient client = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "read", "reason", List.of(call), 100_000, 20),
                new LlmClient.ChatResponse("assistant", "done", "final reason", null, 100_000, 15)));
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(request(workspace))) {
            Agent agent = agent(client, workspace);
            agent.attachSession(handle);

            assertTrue(agent.run("inspect the project files and report findings").contains("done"));
            assertEquals(agent.getConversationHistory(), handle.projection().messages());
            assertEquals(List.of("system", "user", "assistant", "tool", "assistant"),
                    handle.projection().messages().stream().map(LlmClient.Message::role).toList());
            assertTrue(handle.projection().pendingTools().isEmpty());
        }
    }

    @Test
    void successfulRequestAfterRestoreRebuildsUsageAnchor() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("anchor-workspace"));
        RecordingClient client = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "done", null, null, 100_000, 15)), false);
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(request(workspace))) {
            Agent agent = agent(client, workspace);
            agent.attachSession(handle);
            assertEquals(ContextTokenTracker.Mode.FULL_ESTIMATE,
                    agent.currentContextPrediction().mode());

            agent.run("answer this explicit question");

            ContextTokenTracker.ContextPrediction prediction = agent.currentContextPrediction();
            assertEquals(ContextTokenTracker.Mode.USAGE_ANCHORED_DELTA,
                    prediction.mode(), prediction.toString());
        }
    }

    @Test
    void persistenceFailurePreventsProviderCall() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        RecordingClient client = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "must not run", null, null, 1, 1)));
        try (SessionStore store = SessionStore.open(tempDir)) {
            SessionStore.SessionHandle handle = store.create(request(workspace));
            Agent agent = agent(client, workspace);
            agent.attachSession(handle);
            handle.close();

            agent.run("do not send");

            assertEquals(0, client.calls);
        }
    }

    @Test
    void failedProviderRequestIsClosedAsFailed() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        RecordingClient client = new RecordingClient(List.of());
        client.failure = new IOException("provider down");
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(request(workspace))) {
            Agent agent = agent(client, workspace);
            agent.attachSession(handle);

            agent.run("fail once");

            assertTrue(handle.projection().incompleteRequestIds().isEmpty());
            String events = Files.readString(handle.sessionDirectory().resolve("events.jsonl"));
            assertTrue(events.contains(SessionEvent.Types.REQUEST_FAILED));
        }
    }

    private Agent agent(RecordingClient client, Path workspace) {
        StubToolRegistry registry = new StubToolRegistry();
        registry.setProjectPath(workspace.toString());
        return new Agent(client, registry);
    }

    private SessionStore.SessionCreateRequest request(Path workspace) {
        return new SessionStore.SessionCreateRequest(
                workspace, "test-provider", "test-model", null, "react", "agent");
    }

    private static final class StubToolRegistry extends ToolRegistry {
        @Override
        public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
            ToolInvocation invocation = invocations.get(0);
            return List.of(new ToolExecutionResult(invocation.id(), invocation.name(),
                    invocation.argumentsJson(), "tool result", 1L, false, List.of()));
        }
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final boolean supportsTools;
        private int calls;
        private IOException failure;

        private RecordingClient(List<ChatResponse> responses) {
            this(responses, true);
        }

        private RecordingClient(List<ChatResponse> responses, boolean supportsTools) {
            this.responses = new ArrayDeque<>(responses);
            this.supportsTools = supportsTools;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            calls++;
            if (failure != null) {
                throw failure;
            }
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("missing response");
            }
            return response;
        }

        @Override
        public MeasuredUsage normalizeUsage(ChatResponse response) {
            return new MeasuredUsage(response.inputTokens(), response.outputTokens(),
                    response.cachedInputTokens(), MeasuredUsage.InputScope.TOTAL_PROMPT,
                    true, true, true, Instant.now());
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test-provider";
        }

        @Override
        public boolean supportsTools() {
            return supportsTools;
        }
    }
}
