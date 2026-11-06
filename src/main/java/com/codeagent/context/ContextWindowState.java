package com.codeagent.context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Per-execution request state. Durable commits publish only after the sink confirms them. */
public final class ContextWindowState {
    public record Options(boolean enabled, long triggerTokens, long snipTriggerTokens, long snipTargetTokens,
                          long microTriggerTokens, long microTargetTokens, long collapseTriggerTokens,
                          long summaryTargetTokens, int recentToolBatches, int minMicroResultTokens,
                          int minSavingsTokens, int maxCollapseIntervals) {
        public Options {
            if (triggerTokens < 1 || snipTargetTokens < 0 || snipTriggerTokens < snipTargetTokens
                    || microTargetTokens < 0 || microTriggerTokens < microTargetTokens
                    || collapseTriggerTokens < 0 || summaryTargetTokens < 0 || recentToolBatches < 0
                    || minMicroResultTokens < 1 || minSavingsTokens < 1 || maxCollapseIntervals < 1) {
                throw new IllegalArgumentException("Invalid context window options");
            }
        }

        public static Options defaults(ContextProfile profile, boolean enabled) {
            long trigger = profile.compressionTriggerTokens();
            return new Options(enabled, trigger, trigger * 65 / 100, trigger * 60 / 100,
                    trigger * 75 / 100, trigger * 65 / 100, trigger * 90 / 100,
                    trigger * 80 / 100, 2, 2048, 512, 2);
        }
    }

    public record Snapshot(String scopeId, long sourceRevision, long contextGeneration, List<ContextNode> sourceNodes,
                           List<ContextEdit> activeEdits, ProtectionSet protection) {
        public Snapshot {
            sourceNodes = List.copyOf(sourceNodes);
            activeEdits = List.copyOf(activeEdits);
        }
    }

    private final String scopeId;
    private final ContextTokenTracker tracker;
    private final Options options;
    private final SummaryCallBudget summaryBudget;
    private final List<ContextNode> sourceNodes = new ArrayList<>();
    private final Set<NodeId> sourceIds = new HashSet<>();
    private long sourceRevision;
    private long contextGeneration;
    private List<ContextEdit> activeEdits;
    private ProtectionSet hostProtection;

    public ContextWindowState(String scopeId, Options options) {
        this(scopeId, 0, 0, List.of(), List.of(), ProtectionSet.empty(),
                new ContextTokenTracker(), options, new SummaryCallBudget(16));
    }

    public ContextWindowState(String scopeId, long sourceRevision, long contextGeneration,
                              List<ContextNode> sourceNodes, List<ContextEdit> activeEdits,
                              ProtectionSet protection, ContextTokenTracker tracker, Options options,
                              SummaryCallBudget summaryBudget) {
        if (scopeId == null || scopeId.isBlank() || sourceRevision < 0 || contextGeneration < 0) {
            throw new IllegalArgumentException("Invalid context scope");
        }
        this.scopeId = scopeId;
        this.sourceRevision = sourceRevision;
        this.contextGeneration = contextGeneration;
        this.activeEdits = List.copyOf(activeEdits);
        this.hostProtection = Objects.requireNonNull(protection);
        this.tracker = Objects.requireNonNull(tracker);
        this.options = Objects.requireNonNull(options);
        this.summaryBudget = Objects.requireNonNull(summaryBudget);
        for (ContextNode node : sourceNodes) addSource(node);
    }

    public String scopeId() { return scopeId; }
    public ContextTokenTracker tracker() { return tracker; }
    public Options options() { return options; }
    public SummaryCallBudget summaryBudget() { return summaryBudget; }
    public synchronized long sourceRevision() { return sourceRevision; }
    public synchronized long contextGeneration() { return contextGeneration; }
    public synchronized List<ContextNode> sourceNodes() { return List.copyOf(sourceNodes); }
    public synchronized List<ContextEdit> activeEdits() { return activeEdits; }

    public synchronized ProtectionSet protection() {
        return ProtectionSet.derive(sourceNodes, hostProtection, options.recentToolBatches());
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(scopeId, sourceRevision, contextGeneration, sourceNodes, activeEdits, protection());
    }

    public synchronized void append(ContextNode node) {
        addSource(node);
        if (node.id().kind() == NodeId.Kind.RAW) sourceRevision++;
    }

    public synchronized void updateHostProtection(ProtectionSet protection) {
        hostProtection = Objects.requireNonNull(protection);
    }

    public synchronized void publishCommittedEdits(long generation, List<ContextEdit> edits) {
        if (generation <= contextGeneration) throw new IllegalArgumentException("Context generation must advance");
        activeEdits = List.copyOf(edits);
        contextGeneration = generation;
    }

    private void addSource(ContextNode node) {
        NodeId id = node.id();
        if (id.kind() == NodeId.Kind.EDIT || id.kind() == NodeId.Kind.RAW && !scopeId.equals(id.scopeId())
                || !sourceIds.add(id)) {
            throw new IllegalArgumentException("Invalid or duplicate context source identity");
        }
        sourceNodes.add(node);
    }
}
