package com.codeagent.context;

import com.codeagent.llm.LlmClient;
import com.codeagent.prompt.PromptAssembler;
import com.codeagent.prompt.PromptRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextAutoCompactorTest {
    @Test void summarizesEachWholeSourceBeforeReplacingThePrefix() {
        var client = new FixtureClient(-1);
        var snapshot = history();
        var edit = auto(client).propose(snapshot, snapshot.sourceNodes(), 8_000, 3, new SummaryCallBudget(16)).orElseThrow();
        var prefix = snapshot.sourceNodes().subList(1, 5);
        assertEquals(prefix.stream().map(ContextNode::id).toList(), edit.orderedSourceIds());
        assertEquals(ContextEdit.Layer.AUTO, edit.layer());
        assertEquals(SourceKind.GLOBAL_SUMMARY, edit.replacementNodes().get(0).sourceKind());
        for (ContextNode source : prefix) {
            assertEquals(1, client.inputs.stream().filter(input -> input.contains(source.message().content())).count());
        }
        assertTrue(client.inputs.size() >= 3);
        assertTrue(client.inputs.get(client.inputs.size() - 1).contains("priorSummary"));
        assertTrue(client.inputs.stream().noneMatch(input -> input.contains("retained input")));
    }

    @Test void failedChunkAndExhaustedBudgetLeaveOriginalSourcesUntouched() {
        var snapshot = history();
        var failing = new FixtureClient(2);
        assertTrue(auto(failing).propose(snapshot, snapshot.sourceNodes(), 8_000, 3, new SummaryCallBudget(16)).isEmpty());
        assertEquals(2, failing.inputs.size());
        var budget = new SummaryCallBudget(2);
        var limited = new FixtureClient(-1);
        assertTrue(auto(limited).propose(snapshot, snapshot.sourceNodes(), 8_000, 3, budget).isEmpty());
        assertEquals(1, limited.inputs.size());
        assertEquals(8, snapshot.sourceNodes().size());
    }

    private static ContextAutoCompactor auto(FixtureClient client) {
        return new ContextAutoCompactor(new StructuredContextSummarizer(client,
                new PromptAssembler(new PromptRepository(null, null))));
    }

    private static ContextWindowState.Snapshot history() {
        List<ContextNode> nodes = new ArrayList<>();
        nodes.add(node(0, SourceKind.SYSTEM, LlmClient.Message.system("system")));
        nodes.add(node(1, SourceKind.REAL_USER, LlmClient.Message.user("original user goal")));
        for (int i = 2; i < 5; i++) {
            nodes.add(node(i, SourceKind.ASSISTANT, LlmClient.Message.assistant(("analysis part " + i + " abc").repeat(420))));
        }
        for (int i = 5; i < 8; i++) nodes.add(node(i, SourceKind.REAL_USER, LlmClient.Message.user("retained input " + i)));
        return new ContextWindowState.Snapshot("scope", 8, 0, nodes, List.of(),
                ProtectionSet.derive(nodes, ProtectionSet.empty(), 2));
    }

    private static ContextNode node(int seq, SourceKind kind, LlmClient.Message message) {
        return new ContextNode(NodeId.raw("scope", seq), message, kind, "execution", null, null, null);
    }

    private static final class FixtureClient implements LlmClient {
        private final List<String> inputs = new ArrayList<>();
        private final int failAt;
        FixtureClient(int failAt) { this.failAt = failAt; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            assertTrue(tools.isEmpty());
            inputs.add(messages.get(1).content());
            if (inputs.size() == failAt) throw new IOException("transport fixture");
            return new ChatResponse("assistant", """
                    {"summary":"Historical analysis completed.","decisions":[],"openItems":[],
                     "evidenceRefs":[],"goals":[],"constraints":[],"currentState":[]}
                    """, null, null, 20, 5, 0);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }
        @Override public String getModelName() { return "fixture"; }
        @Override public String getProviderName() { return "fixture"; }
    }
}
