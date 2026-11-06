package com.codeagent.context;

import com.codeagent.llm.LlmClient.Message;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.codeagent.context.DeterministicContextEdits.*;

/** Removes old, provably redundant source spans from the request view. */
public final class HistorySnipper {
    private record ResultKey(String tool, JsonNode arguments, String body,
                             ToolResultDescriptor.CaptureCompleteness completeness) {}
    private record BatchKey(String execution, String attempt, List<ResultKey> results) {}
    private record SyntheticKey(String execution, String attempt, String contextKey) {}

    public Optional<ContextEdit> propose(ContextWindowState.Snapshot snapshot, ContextWindowState.Options options) {
        var batches = new HashMap<NodeId, ContextToolBatch>();
        for (var batch : ContextToolBatch.scan(snapshot.sourceNodes())) {
            if (batch.complete()) batches.put(batch.nodes().get(0).id(), batch);
        }
        Map<BatchKey, List<ContextNode>> laterBatches = new HashMap<>();
        Map<SyntheticKey, ContextNode> laterSynthetic = new HashMap<>();
        Set<NodeId> edited = new HashSet<>();
        Set<NodeId> required = new HashSet<>();
        for (var edit : snapshot.activeEdits()) {
            edited.addAll(edit.orderedSourceIds());
            edit.dependencies().forEach(dependency -> required.add(dependency.nodeId()));
        }
        ContextEdit candidate = null;
        for (int i = snapshot.sourceNodes().size() - 1; i >= 0; i--) {
            ContextNode node = snapshot.sourceNodes().get(i);
            var batch = batches.get(node.id());
            List<ContextNode> source;
            List<ContextNode> proof;
            if (batch != null) {
                source = batch.nodes();
                BatchKey key = batchKey(source);
                if (key == null) continue;
                proof = laterBatches.get(key);
                if (source.stream().noneMatch(n -> edited.contains(n.id()))) {
                    laterBatches.putIfAbsent(key, source);
                }
            } else if (plain(node) && node.syntheticContext() != null && node.syntheticContext().completeReplacement()
                    && (node.message().toolCalls() == null || node.message().toolCalls().isEmpty())
                    && node.message().toolCallId() == null
                    && ("user".equals(node.message().role()) || "assistant".equals(node.message().role()))) {
                source = List.of(node);
                var metadata = node.syntheticContext();
                var key = new SyntheticKey(node.executionId(), node.taskAttemptId(), metadata.contextKey());
                var later = laterSynthetic.get(key);
                proof = later != null && later.syntheticContext().generation() > metadata.generation()
                        ? List.of(later) : null;
                if (!edited.contains(node.id())
                        && (later == null || metadata.generation() > later.syntheticContext().generation())) {
                    laterSynthetic.put(key, node);
                }
            } else {
                continue;
            }
            if (proof == null || source.stream().anyMatch(n -> protectedNode(snapshot, n) || required.contains(n.id()))) {
                continue;
            }
            List<NodeId> sourceIds = ids(source);
            var retired = new ArrayList<String>();
            int before = tokens(source);
            boolean conflict = false;
            for (ContextEdit active : snapshot.activeEdits()) {
                if (active.orderedSourceIds().stream().noneMatch(sourceIds::contains)) continue;
                if (active.layer() != ContextEdit.Layer.MICRO || !sourceIds.containsAll(active.orderedSourceIds())) {
                    conflict = true;
                    break;
                }
                retired.add(active.editId());
                before += tokens(active.replacementNodes()) - tokens(source.stream()
                        .filter(n -> active.orderedSourceIds().contains(n.id())).toList());
            }
            if (conflict) continue;
            String editId = editId(snapshot, ContextEdit.Layer.SNIP, node);
            var marker = new ContextNode(NodeId.edit(snapshot.scopeId(), editId, 0),
                    Message.assistant("[历史重复上下文已省略。后续保留来源序号 "
                            + proof.get(0).id().sourceSequence() + "。]"),
                    SourceKind.SYNTHETIC, node.executionId(), node.taskAttemptId(), null, null);
            if (before - tokens(List.of(marker)) < options.minSavingsTokens()) continue;
            var dependencies = proof.stream().map(n -> new ContextEdit.Dependency(n.id(),
                    ContextDigests.sourceDigest(List.of(n)), true)).toList();
            candidate = new ContextEdit(editId, ContextEdit.Layer.SNIP, ContextEdit.Kind.REPLACE_SPAN, sourceIds,
                    ContextDigests.sourceDigest(source), List.of(marker), retired, dependencies, 1);
        }
        return Optional.ofNullable(candidate);
    }

    private static BatchKey batchKey(List<ContextNode> nodes) {
        ContextNode first = nodes.get(0);
        if (first.executionId() == null || first.executionId().isBlank()
                || !empty(first.message().content()) || nodes.stream().anyMatch(n -> !plain(n))) return null;
        var resultsById = new HashMap<String, ContextNode>();
        nodes.subList(1, nodes.size()).forEach(n -> resultsById.put(n.message().toolCallId(), n));
        var results = new ArrayList<ResultKey>();
        for (var call : first.message().toolCalls()) {
            String tool = call.function().name();
            var result = resultsById.get(call.id());
            JsonNode args = arguments(call.function().arguments());
            if (tool == null || !READ_TOOLS.contains(tool) || args == null || !success(result, tool)
                    || result.message().content() == null) return null;
            results.add(new ResultKey(tool, args, result.message().content(), result.descriptor().captureCompleteness()));
        }
        return new BatchKey(first.executionId(), first.taskAttemptId(), List.copyOf(results));
    }
}
