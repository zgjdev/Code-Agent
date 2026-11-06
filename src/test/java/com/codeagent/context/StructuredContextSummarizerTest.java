package com.codeagent.context;

import com.codeagent.llm.LlmClient;
import com.codeagent.prompt.PromptAssembler;
import com.codeagent.prompt.PromptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructuredContextSummarizerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ContextNode SOURCE = new ContextNode(NodeId.raw("fixture", 4),
            LlmClient.Message.assistant("complete-source-".repeat(500)), SourceKind.ASSISTANT,
            "exec", "attempt", null, null);

    @Test void sendsFullSourceWithoutToolsAndReturnsHostCoverage() throws Exception {
        FakeClient client = new FakeClient(validResponse(false));
        var budget = new SummaryCallBudget(16);
        var result = summarizer(client).summarize(List.of(SOURCE), false, 12_000, budget);
        assertEquals(List.of(SOURCE.id()), result.coveredSourceIds());
        assertTrue(client.requests.get(0).get(1).content().contains(SOURCE.message().content()));
        assertTrue(result.text().contains("historical context summary"));
        assertEquals(1, budget.completedCalls());
        assertEquals(10, budget.usage().inputTokens());
    }

    @Test void unknownReferencesAndExtraFieldsAreRepairedButNeverAccepted() throws Exception {
        String unknown = validResponse(false).replace(reference(), "unknown-node");
        String extra = validResponse(false).replace("\"summary\":", "\"success\":true,\"summary\":");
        for (String invalid : List.of(unknown, extra)) {
            FakeClient client = new FakeClient(invalid, invalid);
            var budget = new SummaryCallBudget(16);
            assertThrows(IOException.class, () -> summarizer(client).summarize(List.of(SOURCE), false, 12_000, budget));
            assertEquals(2, client.requests.size());
            assertEquals(2, budget.completedCalls());
            assertEquals(20, budget.usage().inputTokens());
        }
    }

    @Test void repairedResponseUsageIsCountedOncePerAttempt() throws Exception {
        FakeClient client = new FakeClient("invalid-json", validResponse(false));
        var budget = new SummaryCallBudget(2);
        summarizer(client).summarize(List.of(SOURCE), false, 12_000, budget);
        assertEquals(2, budget.completedCalls());
        assertEquals(new SummaryCallBudget.Usage(20, 4, 2), budget.usage());
    }

    @Test void tooLargeSourceAndInsufficientCallBudgetMakeNoRequest() {
        FakeClient client = new FakeClient(validResponse(false));
        assertThrows(IOException.class, () -> summarizer(client).summarize(List.of(SOURCE), false, 1_000,
                new SummaryCallBudget(16)));
        assertThrows(IOException.class, () -> summarizer(client).summarize(List.of(SOURCE), false, 12_000,
                new SummaryCallBudget(1)));
        assertTrue(client.requests.isEmpty());
    }

    @Test void globalSummaryRequiresGoalsConstraintsAndCurrentState() throws Exception {
        FakeClient client = new FakeClient(validResponse(true));
        var summary = summarizer(client).summarize(List.of(SOURCE), true, 12_000, new SummaryCallBudget(16));
        assertTrue(summary.text().contains("constraints"));
        FakeClient missing = new FakeClient(validResponse(false), validResponse(false));
        assertThrows(IOException.class, () -> summarizer(missing).summarize(List.of(SOURCE), true, 12_000,
                new SummaryCallBudget(16)));
    }

    @Test void providerFailureStillConsumesAttemptAndReleasesUnusedRepair() {
        FakeClient client = new FakeClient();
        var budget = new SummaryCallBudget(2);
        assertThrows(IOException.class, () -> summarizer(client).summarize(List.of(SOURCE), false, 12_000, budget));
        assertEquals(1, budget.completedCalls());
        assertEquals(1, budget.remainingCalls());
    }

    @Test void exhaustedExecutionBudgetPreventsRepairAfterChargingTheFirstAttempt() {
        FakeClient client = new FakeClient("invalid-json", validResponse(false));
        var consumed = new java.util.concurrent.atomic.AtomicInteger();
        var budget = new SummaryCallBudget(16);
        var summarizer = new StructuredContextSummarizer(client,
                new PromptAssembler(new PromptRepository(null, null)), () -> consumed.get() == 0,
                response -> consumed.incrementAndGet());
        assertThrows(IOException.class, () -> summarizer.summarize(List.of(SOURCE), false, 12_000, budget));
        assertEquals(1, client.requests.size());
        assertEquals(1, consumed.get());
        assertEquals(1, budget.completedCalls());
    }

    private static StructuredContextSummarizer summarizer(FakeClient client) {
        return new StructuredContextSummarizer(client, new PromptAssembler(new PromptRepository(null, null)));
    }

    private static String reference() { return StructuredContextSummarizer.referenceId(SOURCE.id()); }

    private static String validResponse(boolean global) {
        var root = JSON.createObjectNode().put("summary", "Earlier analysis remains historical and needs current evidence.");
        root.putArray("decisions").addObject().put("text", "Retain constraint").putArray("sourceIds").add(reference());
        root.putArray("openItems");
        root.putArray("evidenceRefs").add(reference());
        if (global) for (String field : List.of("goals", "constraints", "currentState")) root.putArray(field);
        return root.toString();
    }

    private static final class FakeClient implements LlmClient {
        private final List<String> responses;
        private final List<List<Message>> requests = new ArrayList<>();
        FakeClient(String... responses) { this.responses = List.of(responses); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            assertTrue(tools == null || tools.isEmpty());
            requests.add(List.copyOf(messages));
            if (requests.size() > responses.size()) throw new IOException("fixture transport failure");
            return new ChatResponse("assistant", responses.get(requests.size() - 1), null, null, 10, 2, 1);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }
        @Override public String getModelName() { return "fixture"; }
        @Override public String getProviderName() { return "fixture"; }
    }
}
