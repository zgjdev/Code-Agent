package com.codeagent.agent;

import com.codeagent.llm.LlmClient;
import com.codeagent.memory.MemoryEntry;
import com.codeagent.skill.SkillContextBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentClearHistoryTest {

    @TempDir
    Path tempDir;

    @Test
    void clearHistoryRebuildsSystemPromptAndDropsPendingSkillContext() {
        String oldMemoryDir = System.getProperty("codeagent.memory.dir");
        System.setProperty("codeagent.memory.dir", tempDir.toString());
        try {
            RecordingClient llmClient = new RecordingClient(List.of(
                    new LlmClient.ChatResponse("assistant", "ok", null, 50_000, 1_000)
            ));
            Agent agent = new Agent(llmClient);
            SkillContextBuffer skillContextBuffer = new SkillContextBuffer();
            agent.setSkillContextBuffer(skillContextBuffer);
            storeFixture(agent, "clear", "CLEAR_MARKER should only appear when retrieved");

            agent.run("CLEAR_MARKER");

            assertTrue(llmClient.firstUserMessage().contains("CLEAR_MARKER"),
                    "sanity check: the first turn should inject query-specific long-term memory");
            assertFalse(llmClient.firstSystemPrompt().contains("CLEAR_MARKER"),
                    "retrieved memory must stay out of the system prompt to keep the cache prefix stable");
            long beforeClearTokens = agent.currentStatus("idle").totalTokens();

            skillContextBuffer.push("demo", "pending skill body");
            agent.clearHistory();

            List<LlmClient.Message> history = agent.getConversationHistory();
            assertEquals(1, history.size());
            assertFalse(history.get(0).content().contains("CLEAR_MARKER"),
                    "/clear must not preserve the previous query's retrieved memory in system prompt");
            assertFalse(history.get(0).content().contains("## 相关长期记忆"));
            assertEquals("", skillContextBuffer.drain(), "/clear should drop pending skill injection");
            assertTrue(agent.currentStatus("idle").totalTokens() < beforeClearTokens,
                    "status ctx should reflect the cleared conversation instead of the previous LLM usage");
        } finally {
            if (oldMemoryDir == null) {
                System.clearProperty("codeagent.memory.dir");
            } else {
                System.setProperty("codeagent.memory.dir", oldMemoryDir);
            }
        }
    }

    @Test
    void systemPromptStaysIdenticalAcrossTurnsWhileRetrievedMemoryVaries() {
        String oldMemoryDir = System.getProperty("codeagent.memory.dir");
        System.setProperty("codeagent.memory.dir", tempDir.toString());
        try {
            RecordingClient llmClient = new RecordingClient(List.of(
                    new LlmClient.ChatResponse("assistant", "ok", null, 50_000, 1_000),
                    new LlmClient.ChatResponse("assistant", "ok", null, 50_000, 1_000)
            ));
            Agent agent = new Agent(llmClient);
            storeFixture(agent, "alpha", "ALPHA_PROBE 检索探针：偏好 RETRIEVED_TABS 缩进");
            storeFixture(agent, "beta", "BETA_PROBE 检索探针：偏好 RETRIEVED_SPACES 缩进");

            agent.run("ALPHA_PROBE");
            agent.run("BETA_PROBE");

            assertEquals(llmClient.systemPromptOf(0), llmClient.systemPromptOf(1),
                    "a per-turn memory rewrite of the system prompt would break the cache prefix");
            assertTrue(llmClient.lastUserMessageOf(0).contains("RETRIEVED_TABS"),
                    "the first turn must carry its own retrieved memory");
            assertTrue(llmClient.lastUserMessageOf(1).contains("RETRIEVED_SPACES"),
                    "the second turn must carry its own retrieved memory");

            List<LlmClient.Message> firstRequest = llmClient.messagesOf(0);
            List<LlmClient.Message> secondRequest = llmClient.messagesOf(1);
            assertEquals(firstRequest, secondRequest.subList(0, firstRequest.size()),
                    "the previous request must remain a verbatim cache prefix of the next one");
        } finally {
            if (oldMemoryDir == null) {
                System.clearProperty("codeagent.memory.dir");
            } else {
                System.setProperty("codeagent.memory.dir", oldMemoryDir);
            }
        }
    }

    private static void storeFixture(Agent agent, String id, String content) {
        agent.getMemoryManager().getLongTermMemory().store(new MemoryEntry(
                id,
                content,
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"),
                MemoryEntry.estimateTokens(content)));
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> capturedMessages = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            capturedMessages.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 256_000;
        }

        private String firstSystemPrompt() {
            return systemPromptOf(0);
        }

        private String firstUserMessage() {
            return lastUserMessageOf(0);
        }

        private List<Message> messagesOf(int requestIndex) {
            return capturedMessages.get(requestIndex);
        }

        private String systemPromptOf(int requestIndex) {
            return capturedMessages.get(requestIndex).get(0).content();
        }

        private String lastUserMessageOf(int requestIndex) {
            return capturedMessages.get(requestIndex).stream()
                    .filter(message -> "user".equals(message.role()))
                    .map(Message::content)
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new AssertionError("request carried no user message"));
        }
    }
}
