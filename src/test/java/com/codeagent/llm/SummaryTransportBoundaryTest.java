package com.codeagent.llm;

import com.codeagent.context.*;
import com.codeagent.prompt.PromptAssembler;
import com.codeagent.prompt.PromptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SummaryTransportBoundaryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ContextNode SOURCE = new ContextNode(NodeId.raw("summary-test", 1),
            LlmClient.Message.assistant("historical source ".repeat(500)), SourceKind.ASSISTANT,
            "execution", null, null, null);

    @Test void summaryDoesNotHideTransportRetries() throws Exception { verifySingleFailure(503); }
    @Test void summaryDoesNotHideStructuredFallback() throws Exception { verifySingleFailure(400); }
    @Test void summaryDoesNotFollowRedirect() throws Exception { verifySingleFailure(307); }

    private void verifySingleFailure(int status) throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(status)
                    .setHeader("Location", server.url("/redirect"))
                    .setBody("{\"error\":{\"message\":\"unsupported parameter response_format\"}}"));
            server.enqueue(success());
            var budget = new SummaryCallBudget(2);
            var summarizer = new StructuredContextSummarizer(client(server),
                    new PromptAssembler(new PromptRepository(null, null)));
            assertThrows(IOException.class, () -> summarizer.summarize(List.of(SOURCE), false, 12_000, budget));
            assertEquals(1, server.getRequestCount());
            assertEquals(1, budget.completedCalls());
            assertEquals(1, budget.remainingCalls());
        }
    }

    @Test void ordinaryStructuredRequestsRetainRetryAndFallback() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("temporary"));
            server.enqueue(new MockResponse().setResponseCode(400)
                    .setBody("{\"error\":{\"message\":\"unsupported parameter response_format\"}}"));
            server.enqueue(success());
            var spec = new StructuredOutputSpec("fixture", JSON.createObjectNode().put("type", "object"), true);
            assertNotNull(client(server).chatStructured(List.of(LlmClient.Message.user("fixture")), List.of(), spec));
            assertEquals(3, server.getRequestCount());
        }
    }

    private static MockResponse success() {
        var value = JSON.createObjectNode().put("summary", "Historical analysis.");
        value.putArray("decisions"); value.putArray("openItems"); value.putArray("evidenceRefs");
        var event = JSON.createObjectNode();
        var choice = event.putArray("choices").addObject().put("finish_reason", "stop");
        choice.putObject("delta").put("role", "assistant").put("content", value.toString());
        return new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: " + event + "\n\ndata: [DONE]\n\n");
    }

    private static AbstractOpenAiCompatibleClient client(MockWebServer server) {
        return new AbstractOpenAiCompatibleClient() {
            @Override protected String getApiUrl() { return server.url("/chat/completions").toString(); }
            @Override protected String getModel() { return "fixture"; }
            @Override protected String getApiKey() { return "fixture"; }
            @Override public String getModelName() { return "fixture"; }
            @Override public String getProviderName() { return "fixture"; }
            @Override public StructuredOutputCapability structuredOutputCapability() { return StructuredOutputCapability.JSON_SCHEMA; }
            @Override LlmRetryPolicy retryPolicy() {
                return LlmRetryPolicy.forTest(3, 0, 0, () -> 0.5, millis -> {}, Clock.systemUTC());
            }
        };
    }
}
