package com.codeagent.context;

import com.codeagent.llm.LlmClient.Message;
import com.codeagent.llm.LlmClient.ToolCall;
import com.codeagent.tool.ToolOutput.FailureKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HistorySnipperTest {
    @Test
    void removesWholeEquivalentBatchAndRetainsNewestFullProof() {
        String body = "large body ".repeat(500);
        var nodes = new ArrayList<ContextNode>();
        nodes.addAll(batch(1, "one", "exec", "read_file", "{\"path\":\"a\",\"offset\":0}", body));
        nodes.addAll(batch(3, "two", "exec", "read_file", "{\"offset\":0,\"path\":\"a\"}", body));
        nodes.addAll(batch(5, "three", "exec", "read_file", "{\"offset\":0,\"path\":\"a\"}", body));
        var edit = new HistorySnipper().propose(snapshot(nodes, List.of()), options()).orElseThrow();
        assertEquals(List.of(nodes.get(0).id(), nodes.get(1).id()), edit.orderedSourceIds());
        assertEquals(List.of(nodes.get(4).id(), nodes.get(5).id()),
                edit.dependencies().stream().map(ContextEdit.Dependency::nodeId).toList());
        assertTrue(edit.dependencies().stream().allMatch(ContextEdit.Dependency::requiresFullBody));
    }

    @Test
    void neverTreatsDifferentExecutionOrChangedBodyAsProof() {
        String body = "large body ".repeat(500);
        var nodes = new ArrayList<ContextNode>();
        nodes.addAll(batch(1, "one", "exec", "read_file", "{}", body));
        nodes.addAll(batch(3, "two", "other", "read_file", "{}", body));
        nodes.addAll(batch(5, "three", "exec", "read_file", "{}", body + " changed"));
        assertTrue(new HistorySnipper().propose(snapshot(nodes, List.of()), options()).isEmpty());
    }

    @Test
    void replacesOnlyExplicitOlderSyntheticGeneration() {
        var old = new ContextNode(NodeId.raw("scope", 1), Message.user("old diagnostics ".repeat(500)),
                SourceKind.SYNTHETIC, "exec", null, null, null,
                new ContextNode.SyntheticContext("diagnostic/a", 1, true));
        var latest = new ContextNode(NodeId.raw("scope", 2), Message.user("new diagnostics"),
                SourceKind.SYNTHETIC, "exec", null, null, null,
                new ContextNode.SyntheticContext("diagnostic/a", 2, true));
        var edit = new HistorySnipper().propose(snapshot(List.of(old, latest), List.of()), options()).orElseThrow();
        assertEquals(List.of(old.id()), edit.orderedSourceIds());
        assertEquals(latest.id(), edit.dependencies().get(0).nodeId());
    }

    static ContextWindowState.Options options() {
        return new ContextWindowState.Options(true, 1000, 800, 500, 900, 600, 950, 700, 2, 200, 30, 2);
    }

    static ContextWindowState.Snapshot snapshot(List<ContextNode> nodes, List<ContextEdit> edits) {
        return new ContextWindowState.Snapshot("scope", nodes.size(), edits.size(), nodes, edits,
                ProtectionSet.derive(nodes, ProtectionSet.empty(), options().recentToolBatches()));
    }

    static List<ContextNode> batch(int sequence, String id, String execution, String tool, String args, String body) {
        var descriptor = new ToolResultDescriptor(id, tool, ToolResultDescriptor.OutcomeSource.TYPED,
                ToolResultDescriptor.ProcessOutcome.SUCCEEDED, true, "execute_command".equals(tool) ? 0 : null,
                "execute_command".equals(tool) ? "mvn test" : null, FailureKind.NONE, false,
                ToolResultDescriptor.CaptureCompleteness.TRUNCATED, Map.of("path", "a"), null,
                ContextDigests.textDigest(body), List.of(), 24);
        var call = new ToolCall(id, new ToolCall.Function(tool, args));
        return List.of(new ContextNode(NodeId.raw("scope", sequence), Message.assistant("", List.of(call)),
                        SourceKind.TOOL_CALL, execution, null, id, null),
                new ContextNode(NodeId.raw("scope", sequence + 1), Message.tool(id, body),
                        SourceKind.TOOL_RESULT, execution, null, id, descriptor));
    }
}
