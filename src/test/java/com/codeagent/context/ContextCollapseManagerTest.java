package com.codeagent.context;

import com.codeagent.llm.LlmClient;
import com.codeagent.llm.LlmClient.Message;
import com.codeagent.prompt.PromptAssembler;
import com.codeagent.prompt.PromptRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextCollapseManagerTest {
    private static final String BODY = "full-raw-tool-result ".repeat(300);
    private static final String VALID = """
            {"summary":"Historical file reads.","decisions":[],"openItems":[],"evidenceRefs":[]}
            """;

    @Test void summarizesFullOldBatchesAndRetiresTheirOverlays() {
        var nodes = fixture();
        var oldResult = nodes.get(2);
        var micro = new ContextEdit("micro", ContextEdit.Layer.MICRO, ContextEdit.Kind.REPLACE_BODY,
                List.of(oldResult.id()), ContextDigests.sourceDigest(List.of(oldResult)),
                List.of(new ContextNode(NodeId.edit("scope", "micro", 0), Message.tool("call1", "shortened"),
                        SourceKind.TOOL_RESULT, "exec", null, "call1", oldResult.descriptor())),
                List.of(), List.of(), 1);
        var client = new Client(VALID);
        var edit = propose(client, nodes, List.of(micro), 2, 30_000).orElseThrow();

        assertEquals(nodes.subList(1, 5).stream().map(ContextNode::id).toList(), edit.orderedSourceIds());
        assertEquals(List.of("micro"), edit.retiredEditIds());
        assertEquals(SourceKind.LOCAL_SUMMARY, edit.replacementNodes().get(0).sourceKind());
        assertTrue(edit.replacementNodes().get(0).message().content().contains("hostExecutionFacts"));
        assertTrue(client.inputs.get(0).contains(BODY + "call1"));
        assertFalse(client.inputs.get(0).contains("shortened"));
        assertFalse(client.inputs.get(0).contains(BODY + "call4"));
    }

    @Test void retiresSnipWhoseProofIsHiddenAndAccountsForRestoredSource() {
        var nodes = fixture();
        var proof = nodes.get(2);
        var duplicate = new ContextNode(NodeId.raw("scope", 99), Message.assistant("old redundant text"),
                SourceKind.SYNTHETIC, "exec", null, null, null);
        nodes.add(0, duplicate);
        var snip = new ContextEdit("snip", ContextEdit.Layer.SNIP, ContextEdit.Kind.REMOVE_SPAN,
                List.of(duplicate.id()), ContextDigests.sourceDigest(List.of(duplicate)), List.of(), List.of(),
                List.of(new ContextEdit.Dependency(proof.id(), ContextDigests.sourceDigest(List.of(proof)), true)),
                1);

        var client = new Client(VALID);
        var edit = propose(client, nodes, List.of(snip), 2, 30_000).orElseThrow();
        assertEquals(List.of("snip"), edit.retiredEditIds());
        assertFalse(edit.orderedSourceIds().contains(duplicate.id()));

        var largeDuplicate = new ContextNode(duplicate.id(), Message.assistant(BODY.repeat(4)),
                duplicate.sourceKind(), duplicate.executionId(), null, null, null);
        nodes.set(0, largeDuplicate);
        var expensiveSnip = new ContextEdit("snip", snip.layer(), snip.kind(), snip.orderedSourceIds(),
                ContextDigests.sourceDigest(List.of(largeDuplicate)), List.of(), List.of(), snip.dependencies(), 1);
        var second = new Client(VALID);
        assertTrue(propose(second, nodes, List.of(expensiveSnip), 2, 30_000).isEmpty());
        assertTrue(second.inputs.isEmpty());
    }

    @Test void rejectsUnknownReferencesAndOverBudgetOutput() {
        for (String response : List.of(VALID.replace("[]}", "[\"unknown\"]}"),
                VALID.replace("Historical file reads.", "oversized ".repeat(4000)))) {
            var client = new Client(response);
            assertTrue(propose(client, fixture(), List.of(), 2, 30_000).isEmpty());
            assertFalse(client.inputs.isEmpty());
        }
    }

    @Test void leavesProtectedAndOverBudgetBatchesUntouchedWithoutCallingProvider() {
        var client = new Client(VALID);
        assertTrue(propose(client, fixture(), List.of(), 3, 30_000).isEmpty());
        assertTrue(propose(client, fixture(), List.of(), 2, 1_000).isEmpty());
        assertTrue(client.inputs.isEmpty());
    }

    private static List<ContextNode> fixture() {
        var nodes = new ArrayList<ContextNode>();
        nodes.add(new ContextNode(NodeId.raw("scope", 0), Message.user("retain original user input"),
                SourceKind.REAL_USER, "exec", null, null, null));
        for (int i = 1; i <= 4; i++) {
            String id = "call" + i;
            nodes.addAll(HistorySnipperTest.batch(2 * i - 1, id, "exec", "read_file", "{}", BODY + id));
        }
        return nodes;
    }

    private static java.util.Optional<ContextEdit> propose(Client client, List<ContextNode> nodes,
            List<ContextEdit> edits, int recent, long trigger) {
        var snapshot = new ContextWindowState.Snapshot("scope", nodes.size(), 1, nodes, edits,
                ProtectionSet.derive(nodes, ProtectionSet.empty(), recent));
        var options = new ContextWindowState.Options(true, trigger, 0, 0, 0, 0, 0, 0, recent, 2048, 512, 2);
        var manager = new ContextCollapseManager(new StructuredContextSummarizer(client,
                new PromptAssembler(new PromptRepository(null, null))));
        return manager.propose(snapshot, options, new SummaryCallBudget(2));
    }

    private static final class Client implements LlmClient {
        private final List<String> inputs = new ArrayList<>();
        private final String response;

        Client(String response) { this.response = response; }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            assertTrue(tools == null || tools.isEmpty());
            inputs.add(messages.get(1).content());
            return new ChatResponse("assistant", response, null, null, 10, 2, 1);
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }

        @Override public String getModelName() { return "fixture"; }
        @Override public String getProviderName() { return "fixture"; }
    }
}
