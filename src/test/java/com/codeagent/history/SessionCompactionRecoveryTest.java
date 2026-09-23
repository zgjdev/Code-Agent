package com.codeagent.history;

import com.codeagent.agent.Agent;
import com.codeagent.context.MeasuredUsage;
import com.codeagent.llm.ContextWindowExceededException;
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

class SessionCompactionRecoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void clearAndManualCompactionSurviveRestart() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        String id;
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(request(workspace))) {
            id = handle.sessionId();
            Agent agent = agent(new QueueClient(List.of(
                    response("a1"), response("a2"), response("a3"), response("summary"))), workspace);
            agent.attachSession(handle);
            agent.run("answer question one");
            agent.run("answer question two");
            agent.run("answer question three");

            assertTrue(agent.compactHistoryNow().compacted());
            assertEquals(agent.getConversationHistory(), handle.projection().messages());
        }

        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle resumed = store.resumeWritable(id, workspace)) {
            assertTrue(resumed.projection().messages().stream()
                    .anyMatch(message -> message.content() != null && message.content().contains("summary")));
            Agent restored = agent(new QueueClient(List.of()), workspace);
            restored.attachSession(resumed);
            restored.clearHistory();
            assertEquals(1, restored.getConversationHistory().size());
            assertEquals(restored.getConversationHistory(), resumed.projection().messages());
        }

        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle resumed = store.resumeWritable(id, workspace)) {
            assertEquals(1, resumed.projection().messages().size());
            assertEquals("system", resumed.projection().messages().get(0).role());
        }
    }

    @Test
    void providerOverflowRecoveryCompactionSurvivesRestart() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("overflow-workspace"));
        String id;
        try (SessionStore store = SessionStore.open(tempDir.resolve("overflow-history"));
             SessionStore.SessionHandle handle = store.create(request(workspace))) {
            id = handle.sessionId();
            OverflowRecoveryClient client = new OverflowRecoveryClient();
            Agent agent = agent(client, workspace);
            agent.attachSession(handle);

            assertEquals("a1", agent.run("first question"));
            assertEquals("a2", agent.run("second question"));
            assertEquals("done", agent.run("third question triggers overflow"));

            assertEquals(agent.getConversationHistory(), handle.projection().messages(),
                    "overflow recovery compaction must be committed to the durable provider surface");
            assertTrue(handle.projection().messages().stream()
                    .anyMatch(message -> message.content() != null
                            && message.content().contains("overflow-summary")));
        }

        try (SessionStore store = SessionStore.open(tempDir.resolve("overflow-history"));
             SessionStore.SessionHandle resumed = store.resumeWritable(id, workspace)) {
            assertTrue(resumed.projection().messages().stream()
                    .anyMatch(message -> message.content() != null
                            && message.content().contains("overflow-summary")),
                    "restart must recover the overflow-compacted surface, not the pre-compaction history");
        }
    }

    private Agent agent(LlmClient client, Path workspace) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toString());
        return new Agent(client, registry);
    }

    private SessionStore.SessionCreateRequest request(Path workspace) {
        return new SessionStore.SessionCreateRequest(
                workspace, "test", "test-model", null, "react", "agent");
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, null, 100_000, 10);
    }

    private static final class OverflowRecoveryClient implements LlmClient {
        private int calls;

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            calls++;
            return switch (calls) {
                case 1 -> response("a1");
                case 2 -> response("a2");
                case 3 -> throw new ContextWindowExceededException("forced overflow");
                case 4 -> response("overflow-summary");
                case 5 -> response("done");
                default -> throw new IOException("unexpected call " + calls);
            };
        }

        @Override
        public MeasuredUsage normalizeUsage(ChatResponse response) {
            return new MeasuredUsage(10, 2, 0,
                    MeasuredUsage.InputScope.TOTAL_PROMPT, true, true, true, Instant.now());
        }

        @Override public String getModelName() { return "test-model"; }
        @Override public String getProviderName() { return "test"; }
        @Override public boolean supportsTools() { return false; }
    }

    private static final class QueueClient implements LlmClient {
        private final Queue<ChatResponse> responses;

        private QueueClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) throw new IOException("missing response");
            return response;
        }

        @Override
        public MeasuredUsage normalizeUsage(ChatResponse response) {
            return new MeasuredUsage(response.inputTokens(), response.outputTokens(), 0,
                    MeasuredUsage.InputScope.TOTAL_PROMPT, true, true, true, Instant.now());
        }

        @Override public String getModelName() { return "test-model"; }
        @Override public String getProviderName() { return "test"; }
        @Override public boolean supportsTools() { return false; }
    }
}
