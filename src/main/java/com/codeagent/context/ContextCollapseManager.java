package com.codeagent.context;

import com.codeagent.llm.LlmClient.Message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.codeagent.context.DeterministicContextEdits.*;

/** Selects and summarizes a closed raw span. The caller owns commit and stale-candidate retry. */
public final class ContextCollapseManager {
    private final StructuredContextSummarizer summarizer;

    public ContextCollapseManager(StructuredContextSummarizer summarizer) {
        this.summarizer = Objects.requireNonNull(summarizer);
    }

    public Optional<ContextEdit> propose(ContextWindowState.Snapshot snapshot, ContextWindowState.Options options,
                                         SummaryCallBudget budget) {
        try {
            var state = new ContextScopeState(snapshot.scopeId(), snapshot.sourceRevision(),
                    snapshot.contextGeneration(), snapshot.sourceNodes(), snapshot.activeEdits());
            int before = tokens(ContextEditReplay.project(state, snapshot.protection()));
            var groups = groups(snapshot);
            for (int start = 0; start < groups.size(); start++) {
                var candidate = candidate(snapshot, groups, start, options.triggerTokens());
                if (candidate == null) continue;
                var provisional = edit(snapshot, candidate, "");
                var provisionalState = apply(state, snapshot, provisional);
                int available = before - tokens(ContextEditReplay.project(provisionalState, snapshot.protection()));
                if (available < Math.max(512, options.minSavingsTokens())) continue;

                var summary = summarizer.summarize(candidate.nodes(), false, options.triggerTokens(), budget, false);
                var edit = edit(snapshot, candidate, "[历史过程摘要]\n" + summary.text());
                var next = apply(state, snapshot, edit);
                int netSavings = before - tokens(ContextEditReplay.project(next, snapshot.protection()));
                int replacementTokens = tokens(edit.replacementNodes());
                if (netSavings >= Math.max(512, options.minSavingsTokens()) && netSavings >= replacementTokens) {
                    return Optional.of(edit);
                }
                return Optional.empty();
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // A failed summary or invalid projected edit leaves the prior context intact.
        }
        return Optional.empty();
    }

    private record Group(List<ContextNode> nodes, boolean toolBatch, boolean eligible) {}
    private record Candidate(List<ContextNode> nodes, List<String> retired) {}

    private Candidate candidate(ContextWindowState.Snapshot snapshot, List<Group> groups,
                                int start, long triggerTokens) throws IOException {
        Group first = groups.get(start);
        if (!first.eligible()) return null;
        var nodes = new ArrayList<ContextNode>();
        int batches = 0;
        Candidate best = null;
        for (int end = start; end < groups.size(); end++) {
            Group group = groups.get(end);
            if (!group.eligible() || !sameOwner(first.nodes().get(0), group.nodes().get(0))) break;
            nodes.addAll(group.nodes());
            if (!summarizer.canFit(nodes, false, triggerTokens)) break;
            if (group.toolBatch()) batches++;
            if (batches < 2) continue;
            var retired = retirements(snapshot.activeEdits(), new HashSet<>(ids(nodes)));
            if (retired != null) best = new Candidate(List.copyOf(nodes), retired);
        }
        return best;
    }

    private static List<Group> groups(ContextWindowState.Snapshot snapshot) {
        var source = snapshot.sourceNodes();
        var batches = new HashMap<NodeId, ContextToolBatch>();
        ContextToolBatch.scan(source).forEach(b -> batches.put(b.nodes().get(0).id(), b));
        Set<NodeId> summaries = new HashSet<>();
        for (var edit : snapshot.activeEdits()) {
            if (edit.layer() == ContextEdit.Layer.COLLAPSE || edit.layer() == ContextEdit.Layer.AUTO) {
                summaries.addAll(edit.orderedSourceIds());
            }
        }
        var groups = new ArrayList<Group>();
        for (int index = 0; index < source.size();) {
            ContextNode node = source.get(index);
            var batch = batches.get(node.id());
            var nodes = batch == null ? List.of(node) : batch.nodes();
            boolean eligible = (batch == null ? "assistant".equals(node.message().role()) : batch.complete())
                    && nodes.stream().allMatch(n -> eligible(snapshot, summaries, n));
            groups.add(new Group(nodes, batch != null, eligible));
            index += nodes.size();
        }
        return groups;
    }

    private static boolean eligible(ContextWindowState.Snapshot snapshot, Set<NodeId> summaries, ContextNode node) {
        return node.id().kind() == NodeId.Kind.RAW && !protectedNode(snapshot, node) && !summaries.contains(node.id())
                && switch (node.sourceKind()) {
                    case ASSISTANT, TOOL_CALL, TOOL_RESULT, SYNTHETIC -> true;
                    default -> false;
                };
    }

    private static boolean sameOwner(ContextNode first, ContextNode other) {
        return Objects.equals(first.executionId(), other.executionId())
                && Objects.equals(first.taskAttemptId(), other.taskAttemptId());
    }

    private static List<String> retirements(List<ContextEdit> active, Set<NodeId> sources) {
        Set<String> retired = new HashSet<>();
        Set<NodeId> removed = new HashSet<>(sources);
        for (var edit : active) {
            if (edit.orderedSourceIds().stream().noneMatch(sources::contains)) continue;
            if (!sources.containsAll(edit.orderedSourceIds())) return null;
            retired.add(edit.editId());
            edit.replacementNodes().forEach(node -> removed.add(node.id()));
        }
        boolean changed;
        do {
            changed = false;
            for (var edit : active) {
                if (retired.contains(edit.editId())
                        || edit.dependencies().stream().noneMatch(dep -> removed.contains(dep.nodeId()))) continue;
                if (edit.layer() != ContextEdit.Layer.SNIP) return null;
                retired.add(edit.editId());
                edit.replacementNodes().forEach(node -> removed.add(node.id()));
                changed = true;
            }
        } while (changed);
        return active.stream().map(ContextEdit::editId).filter(retired::contains).toList();
    }

    private static ContextEdit edit(ContextWindowState.Snapshot snapshot, Candidate candidate, String text) {
        var first = candidate.nodes().get(0);
        String id = editId(snapshot, ContextEdit.Layer.COLLAPSE, first);
        var replacement = new ContextNode(NodeId.edit(snapshot.scopeId(), id, 0), Message.assistant(text),
                SourceKind.LOCAL_SUMMARY, first.executionId(), first.taskAttemptId(), null, null);
        return new ContextEdit(id, ContextEdit.Layer.COLLAPSE, ContextEdit.Kind.REPLACE_SPAN,
                ids(candidate.nodes()), ContextDigests.sourceDigest(candidate.nodes()),
                List.of(replacement), candidate.retired(), List.of(), 1);
    }

    private static ContextScopeState apply(ContextScopeState state, ContextWindowState.Snapshot snapshot,
                                           ContextEdit edit) {
        return ContextEditReplay.apply(state, new ContextEditBatch(edit.editId(), state.scopeId(),
                state.sourceRevision(), state.contextGeneration(), snapshot.protection().digest(),
                ContextEditReplay.baseDigest(state), List.of(edit)), snapshot.protection());
    }
}
