package com.codeagent.context;

import com.codeagent.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContextProtectionTest {
    @Test
    void closesAtEofWithResultsInTheirOriginalOrder() {
        var call = node(1, LlmClient.Message.assistant("", List.of(call("a"), call("b"))));
        var b = node(2, LlmClient.Message.tool("b", "second"));
        var a = node(3, LlmClient.Message.tool("a", "first"));
        var batch = ContextToolBatch.scan(List.of(call, b, a)).get(0);
        assertTrue(batch.complete());
        assertEquals(List.of(call, b, a), batch.nodes());
    }

    @Test
    void protectsMalformedGroupsAndDoesNotTreatSyntheticUserAsRealUser() {
        var call = node(1, LlmClient.Message.assistant("", List.of(call("a"), call("b"))));
        var first = node(2, LlmClient.Message.tool("a", ""));
        var duplicate = node(3, LlmClient.Message.tool("a", ""));
        var synthetic = node(4, LlmClient.Message.user("diagnostics"));
        var protection = ProtectionSet.derive(List.of(call, first, duplicate, synthetic), ProtectionSet.empty(), 2);
        assertEquals(Set.of(call.id(), first.id(), duplicate.id()), protection.protectedNodeIds());
        assertFalse(protection.boundaryNodeIds().contains(synthetic.id()));
    }

    @Test
    void sourceIdentitiesAndDigestsAreScopeSensitive() {
        var a = node(1, LlmClient.Message.assistant("same"));
        var b = new ContextNode(NodeId.raw("another-scope", 1), a.message(), a.sourceKind(), null, null, null, null);
        assertNotEquals(a.id(), b.id());
        assertNotEquals(ContextDigests.sourceDigest(List.of(a)), ContextDigests.sourceDigest(List.of(b)));
        assertThrows(IllegalArgumentException.class, () -> NodeId.raw("scope", -1));
    }

    private static ContextNode node(long sequence, LlmClient.Message message) {
        return new ContextNode(NodeId.raw("scope", sequence), message, SourceKind.ASSISTANT, null, null, null, null);
    }

    private static LlmClient.ToolCall call(String id) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function("read_file", "{}"));
    }
}
