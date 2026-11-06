package com.codeagent.context;

import com.codeagent.memory.TokenBudget;
import com.codeagent.tool.ToolOutput.FailureKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Objects;
import java.util.Set;

final class DeterministicContextEdits {
    static final Set<String> READ_TOOLS = Set.of("read_file", "list_dir", "glob_files", "grep_code", "search_code");
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    private DeterministicContextEdits() {}

    static JsonNode arguments(String value) {
        if (value == null) return null;
        try {
            JsonNode parsed = JSON.readTree(value);
            return parsed.isObject() ? parsed : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static boolean plain(ContextNode node) {
        return node.id().kind() == NodeId.Kind.RAW && !node.message().hasContentParts()
                && empty(node.message().reasoningContent())
                && node.sourceKind() != SourceKind.PROVIDER_OPAQUE;
    }

    static boolean empty(String text) {
        return text == null || text.isEmpty();
    }

    static boolean success(ContextNode node, String tool) {
        var d = node.descriptor();
        return d != null && Objects.equals(d.toolName(), tool)
                && d.outcomeSource() == ToolResultDescriptor.OutcomeSource.TYPED
                && d.processOutcome() == ToolResultDescriptor.ProcessOutcome.SUCCEEDED
                && Boolean.TRUE.equals(d.successful()) && !d.observationUnknown()
                && (d.failureKind() == null || d.failureKind() == FailureKind.NONE);
    }

    static boolean protectedNode(ContextWindowState.Snapshot snapshot, ContextNode node) {
        return snapshot.protection().protectedNodeIds().contains(node.id())
                || snapshot.protection().boundaryNodeIds().contains(node.id());
    }

    static List<NodeId> ids(List<ContextNode> nodes) {
        return nodes.stream().map(ContextNode::id).toList();
    }

    static int tokens(List<ContextNode> nodes) {
        return TokenBudget.estimateMessagesTokens(nodes.stream().map(ContextNode::message).toList());
    }

    static String editId(ContextWindowState.Snapshot snapshot, ContextEdit.Layer layer, ContextNode first) {
        return layer.name().toLowerCase(java.util.Locale.ROOT) + "-" + snapshot.contextGeneration()
                + "-" + first.id().sourceSequence();
    }
}
