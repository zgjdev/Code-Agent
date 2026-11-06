package com.codeagent.context;

import com.codeagent.llm.LlmClient;
import com.codeagent.memory.TokenBudget;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Proposes one global replacement after every source group has been summarized within the call limit. */
public final class ContextAutoCompactor {
    private final StructuredContextSummarizer summarizer;

    public ContextAutoCompactor(StructuredContextSummarizer summarizer) {
        this.summarizer = summarizer;
    }

    public Optional<ContextEdit> propose(ContextWindowState.Snapshot snapshot, List<ContextNode> currentView,
                                         long triggerTokens, int retainTurns, SummaryCallBudget budget) {
        List<ContextNode> prefix = eligiblePrefix(snapshot, retainTurns);
        if (prefix.isEmpty()) return Optional.empty();
        List<NodeId> ids = prefix.stream().map(ContextNode::id).toList();
        Set<NodeId> covered = new HashSet<>(ids);
        List<ContextEdit> retired = new ArrayList<>();
        for (ContextEdit existing : snapshot.activeEdits()) {
            if (existing.orderedSourceIds().stream().noneMatch(covered::contains)) continue;
            if (!covered.containsAll(existing.orderedSourceIds())) return Optional.empty();
            retired.add(existing);
        }
        retired.forEach(edit -> edit.replacementNodes().forEach(node -> covered.add(node.id())));
        if (snapshot.activeEdits().stream().filter(edit -> !retired.contains(edit))
                .flatMap(edit -> edit.dependencies().stream()).anyMatch(dep -> covered.contains(dep.nodeId()))) {
            return Optional.empty();
        }
        int before = TokenBudget.estimateMessagesTokens(currentView.stream()
                .filter(node -> covered.contains(node.id())).map(ContextNode::message).toList());
        if (before < 512) return Optional.empty();
        try {
            var summary = summarize(prefix, triggerTokens, budget);
            if (!summary.coveredSourceIds().equals(ids)) return Optional.empty();
            var message = LlmClient.Message.assistant(summary.text());
            int after = TokenBudget.estimateMessageTokens(message);
            if (before - after < 512 || after > before / 2) return Optional.empty();
            String editId = UUID.randomUUID().toString();
            var replacement = new ContextNode(NodeId.edit(snapshot.scopeId(), editId, 0), message,
                    SourceKind.GLOBAL_SUMMARY, null, null, null, null);
            return Optional.of(new ContextEdit(editId, ContextEdit.Layer.AUTO, ContextEdit.Kind.REPLACE_SPAN,
                    ids, ContextDigests.sourceDigest(prefix), List.of(replacement),
                    retired.stream().map(ContextEdit::editId).toList(), List.of(), 1));
        } catch (IOException e) {
            // Partial or invalid summaries do not alter the active context.
            return Optional.empty();
        }
    }

    private static List<ContextNode> eligiblePrefix(ContextWindowState.Snapshot snapshot, int retainTurns) {
        List<ContextNode> source = snapshot.sourceNodes();
        int remaining = Math.max(1, retainTurns);
        int cut = -1;
        for (int i = source.size() - 1; i >= 0; i--) {
            if (source.get(i).sourceKind() == SourceKind.REAL_USER && --remaining == 0) {
                cut = i;
                break;
            }
        }
        if (cut < 0) return List.of();
        int start = 0;
        while (start < cut && "system".equals(source.get(start).message().role())) start++;
        List<ContextNode> prefix = source.subList(start, cut);
        if (prefix.stream().noneMatch(node -> node.sourceKind() == SourceKind.REAL_USER)
                || prefix.stream().anyMatch(node -> node.id().kind() != NodeId.Kind.RAW
                        || snapshot.protection().protectedNodeIds().contains(node.id()))) {
            return List.of();
        }
        return prefix;
    }

    private StructuredContextSummarizer.Summary summarize(List<ContextNode> source, long triggerTokens,
                                                           SummaryCallBudget budget) throws IOException {
        List<List<ContextNode>> groups = groups(source);
        List<StructuredContextSummarizer.Summary> summaries = new ArrayList<>();
        List<ContextNode> chunk = new ArrayList<>();
        for (List<ContextNode> group : groups) {
            List<ContextNode> combined = join(chunk, group);
            if (summarizer.canFit(combined, triggerTokens)) {
                chunk = combined;
            } else {
                if (chunk.isEmpty() || !summarizer.canFit(group, triggerTokens)) {
                    throw new IOException("SUMMARY_GROUP_OVER_BUDGET");
                }
                summaries.add(summarizer.summarize(chunk, true, triggerTokens, budget, false));
                chunk = new ArrayList<>(group);
            }
        }
        summaries.add(summarizer.summarize(chunk, true, triggerTokens, budget, false));
        Map<NodeId, ContextNode> byId = new HashMap<>();
        source.forEach(node -> byId.put(node.id(), node));
        while (summaries.size() > 1) {
            List<StructuredContextSummarizer.Summary> next = new ArrayList<>();
            List<StructuredContextSummarizer.Summary> chunkSummaries = new ArrayList<>();
            for (var summary : summaries) {
                var candidate = join(chunkSummaries, List.of(summary));
                if (!chunkSummaries.isEmpty() && !summarizer.canFitAggregates(candidate, triggerTokens)) {
                    next.add(aggregate(chunkSummaries, byId, triggerTokens, budget));
                    chunkSummaries = new ArrayList<>();
                }
                chunkSummaries.add(summary);
            }
            next.add(aggregate(chunkSummaries, byId, triggerTokens, budget));
            if (next.size() >= summaries.size()) throw new IOException("SUMMARY_AGGREGATION_NO_PROGRESS");
            summaries = next;
        }
        return summaries.get(0);
    }

    private StructuredContextSummarizer.Summary aggregate(List<StructuredContextSummarizer.Summary> chunks,
                                                           Map<NodeId, ContextNode> byId, long triggerTokens,
                                                           SummaryCallBudget budget) throws IOException {
        if (chunks.size() == 1) return chunks.get(0);
        List<ContextNode> source = chunks.stream().flatMap(summary -> summary.coveredSourceIds().stream())
                .map(byId::get).toList();
        return summarizer.aggregate(chunks, source, triggerTokens, budget);
    }

    private static List<List<ContextNode>> groups(List<ContextNode> source) throws IOException {
        Map<NodeId, ContextToolBatch> batches = new HashMap<>();
        for (ContextToolBatch batch : ContextToolBatch.scan(source)) {
            if (!batch.complete()) throw new IOException("SUMMARY_INCOMPLETE_TOOL_GROUP");
            batches.put(batch.nodes().get(0).id(), batch);
        }
        List<List<ContextNode>> result = new ArrayList<>();
        for (int i = 0; i < source.size();) {
            ContextNode node = source.get(i);
            ContextToolBatch batch = batches.get(node.id());
            List<ContextNode> group = batch == null ? List.of(node) : batch.nodes();
            result.add(group);
            i += group.size();
        }
        return result;
    }

    private static <T> List<T> join(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }
}
