package com.codeagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredJsonExecutorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final StructuredOutputSpec SPEC = new StructuredOutputSpec(
            "decision",
            JSON.createObjectNode()
                    .put("type", "object")
                    .set("properties", JSON.createObjectNode()
                            .set("mode", JSON.createObjectNode().put("type", "string"))),
            true);

    @Test
    void returnsFirstValidResponseWithoutRepair() throws Exception {
        RecordingClient client = new RecordingClient(response("{\"mode\":\"react\"}", 10, 2));

        StructuredJsonExecutor.Result<String> result = new StructuredJsonExecutor().execute(
                client,
                List.of(LlmClient.Message.system("Return JSON")),
                null,
                SPEC,
                false,
                StructuredJsonExecutorTest::decodeMode,
                LlmClient.StreamListener.NO_OP);

        assertEquals("react", result.value());
        assertEquals(1, client.calls);
        assertEquals(10, result.response().inputTokens());
        assertEquals(2, result.response().outputTokens());
    }

    @Test
    void repairsMalformedJsonOnceAndAggregatesUsage() throws Exception {
        RecordingClient client = new RecordingClient(
                response("not json", 10, 2),
                response("{\"mode\":\"plan\"}", 12, 3));

        StructuredJsonExecutor.Result<String> result = new StructuredJsonExecutor().execute(
                client,
                List.of(LlmClient.Message.system("Return JSON")),
                null,
                SPEC,
                false,
                StructuredJsonExecutorTest::decodeMode,
                LlmClient.StreamListener.NO_OP);

        assertEquals("plan", result.value());
        assertEquals(2, client.calls);
        assertEquals(22, result.response().inputTokens());
        assertEquals(5, result.response().outputTokens());
        assertTrue(client.requests.get(1).get(client.requests.get(1).size() - 1).content()
                .contains("只返回修正后的 JSON"));
    }

    @Test
    void repairsSyntacticallyValidButRejectedBusinessValue() throws Exception {
        RecordingClient client = new RecordingClient(
                response("{\"mode\":\"PLAN\"}", 3, 1),
                response("{\"mode\":\"plan\"}", 4, 1));

        StructuredJsonExecutor.Result<String> result = new StructuredJsonExecutor().execute(
                client,
                List.of(LlmClient.Message.system("Return JSON")),
                null,
                SPEC,
                false,
                StructuredJsonExecutorTest::decodeMode,
                LlmClient.StreamListener.NO_OP);

        assertEquals("plan", result.value());
        assertEquals(2, client.calls);
    }

    @Test
    void throwsAfterBoundedAttempts() {
        RecordingClient client = new RecordingClient(
                response("bad", 1, 1),
                response("still bad", 1, 1));

        StructuredOutputException error = assertThrows(StructuredOutputException.class,
                () -> new StructuredJsonExecutor().execute(
                        client,
                        List.of(LlmClient.Message.system("Return JSON")),
                        null,
                        SPEC,
                        false,
                        StructuredJsonExecutorTest::decodeMode,
                        LlmClient.StreamListener.NO_OP));

        assertEquals(2, client.calls);
        assertEquals(2, error.response().inputTokens());
    }

    @Test
    void markdownFenceIsAcceptedOnlyWhenRequested() throws Exception {
        String fenced = "```json\n{\"mode\":\"react\"}\n```";
        RecordingClient allowed = new RecordingClient(response(fenced, 1, 1));
        assertEquals("react", new StructuredJsonExecutor().execute(
                allowed, List.of(), null, SPEC, true,
                StructuredJsonExecutorTest::decodeMode, LlmClient.StreamListener.NO_OP).value());

        RecordingClient strict = new RecordingClient(
                response(fenced, 1, 1),
                response("{\"mode\":\"react\"}", 1, 1));
        assertEquals("react", new StructuredJsonExecutor().execute(
                strict, List.of(), null, SPEC, false,
                StructuredJsonExecutorTest::decodeMode, LlmClient.StreamListener.NO_OP).value());
        assertEquals(2, strict.calls);
    }

    private static String decodeMode(JsonNode root) throws IOException {
        if (!root.isObject() || root.size() != 1 || !root.path("mode").isTextual()) {
            throw new IOException("mode object invalid");
        }
        String mode = root.path("mode").asText();
        if (!mode.equals("react") && !mode.equals("plan")) {
            throw new IOException("mode enum invalid");
        }
        return mode;
    }

    private static LlmClient.ChatResponse response(String content, int input, int output) {
        return new LlmClient.ChatResponse("assistant", content, null, null, input, output, 0);
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> requests = new ArrayList<>();
        private int calls;

        private RecordingClient(ChatResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            calls++;
            requests.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("no response");
            }
            return response;
        }

        @Override
        public ChatResponse chatStructured(List<Message> messages, List<Tool> tools,
                                           StructuredOutputSpec spec, StreamListener listener)
                throws IOException {
            return chat(messages, tools, listener);
        }

        @Override
        public String getModelName() {
            return "structured-test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
