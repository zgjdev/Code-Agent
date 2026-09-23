package com.codeagent.agent;

import com.codeagent.llm.LlmClient;
import com.codeagent.prompt.ModeRouterPromptBuilder;
import com.codeagent.prompt.PromptRepository;
import com.codeagent.runtime.CancellationContext;
import com.codeagent.runtime.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionModeRouterTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsStrictReactAndPlanResponsesWithoutTools() {
        StubClient reactClient = new StubClient(response("{\"mode\":\"react\"}"));
        StubClient planClient = new StubClient(response("{\"mode\":\"plan\"}"));

        assertEquals(ExecutionMode.REACT, router(reactClient).route("task", List.of()).mode());
        assertEquals(ExecutionMode.PLAN, router(planClient).route("task", List.of()).mode());
        assertNull(reactClient.tools);
        assertNull(planClient.tools);
    }

    @Test
    void capturesNormalizedUsageForModelDecision() {
        RoutingDecision decision = router(new StubClient(new LlmClient.ChatResponse(
                "assistant", "{\"mode\":\"react\"}", null, null, 17, 3, 5)))
                .route("task", List.of());

        assertEquals(RoutingSource.AUTO_MODEL, decision.source());
        assertEquals(17, decision.usage().orElseThrow().inputTokens());
        assertEquals(3, decision.usage().orElseThrow().outputTokens());
        assertEquals(5, decision.usage().orElseThrow().cachedInputTokens());
    }

    @Test
    void rejectsEveryNonCanonicalResponseAndFallsBackToReact() {
        List<String> invalid = java.util.Arrays.asList(
                "react",
                "\"plan\"",
                "{\"mode\":\"PLAN\"}",
                "{\"mode\":\"plan\",\"confidence\":1}",
                "```json\n{\"mode\":\"plan\"}\n```",
                "[]",
                "{}",
                "",
                null
        );

        for (String content : invalid) {
            RoutingDecision decision = router(new StubClient(response(content))).route("task", List.of());
            assertEquals(ExecutionMode.REACT, decision.mode(), "content=" + content);
            assertEquals(RoutingSource.AUTO_FALLBACK, decision.source(), "content=" + content);
        }
    }

    @Test
    void providerFailureFallsBackWhenTurnIsNotCancelled() {
        RoutingDecision decision = router(new StubClient(new IOException("network")))
                .route("task", List.of());

        assertEquals(ExecutionMode.REACT, decision.mode());
        assertEquals(RoutingSource.AUTO_FALLBACK, decision.source());
        assertFalse(decision.usage().isPresent());
    }

    @Test
    void usageNormalizationFailureFallsBackWithoutRetryingNormalization() {
        StubClient client = new StubClient(response("{\"mode\":\"plan\"}")) {
            @Override
            public com.codeagent.context.MeasuredUsage normalizeUsage(ChatResponse response) {
                throw new IllegalStateException("invalid usage");
            }
        };

        RoutingDecision decision = router(client).route("task", List.of());

        assertEquals(ExecutionMode.REACT, decision.mode());
        assertEquals(RoutingSource.AUTO_FALLBACK, decision.source());
        assertFalse(decision.usage().isPresent());
    }

    @Test
    void cancellationDuringProviderFailurePropagatesInsteadOfFallingBack() {
        CancellationToken token = CancellationContext.startRun();
        try {
            StubClient client = new StubClient(() -> {
                token.cancel();
                throw new IOException("cancelled transport");
            });

            assertThrows(CancellationException.class,
                    () -> router(client).route("task", List.of()));
        } finally {
            CancellationContext.clear(token);
        }
    }

    private ExecutionModeRouter router(StubClient client) {
        PromptRepository repository = new PromptRepository(
                tempDir.resolve("user"), tempDir.resolve("project"));
        return new ExecutionModeRouter(client, new ModeRouterPromptBuilder(repository));
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, null, 2, 1, 0);
    }

    private static class StubClient implements LlmClient {
        private final ResponseSupplier supplier;
        private List<Tool> tools;

        private StubClient(LlmClient.ChatResponse response) {
            this(() -> response);
        }

        private StubClient(IOException failure) {
            this(() -> { throw failure; });
        }

        private StubClient(ResponseSupplier supplier) {
            this.supplier = supplier;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            this.tools = tools;
            return supplier.get();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "router-test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }

    @FunctionalInterface
    private interface ResponseSupplier {
        LlmClient.ChatResponse get() throws IOException;
    }
}
