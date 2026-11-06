package com.codeagent.context;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;

import static com.codeagent.context.ToolResultDescriptor.ProcessOutcome;

/** Host protection and source boundaries. Source-derived protection only adds to host pins. */
public record ProtectionSet(Set<NodeId> protectedNodeIds, Set<NodeId> boundaryNodeIds,
                            Set<NodeId> unfinishedToolNodeIds, Set<NodeId> recoveryNodeIds,
                            Set<NodeId> evidenceNodeIds, Map<String, String> requestFingerprints) {
    private static final Set<String> READ_ONLY = Set.of(
            "read_file", "list_dir", "glob_files", "grep_code", "search_code", "web_search", "web_fetch");

    public ProtectionSet(Set<NodeId> protectedNodeIds, Set<NodeId> boundaryNodeIds,
                         Set<NodeId> unfinishedToolNodeIds, Set<NodeId> recoveryNodeIds,
                         Set<NodeId> evidenceNodeIds) {
        this(protectedNodeIds, boundaryNodeIds, unfinishedToolNodeIds, recoveryNodeIds, evidenceNodeIds, Map.of());
    }

    public ProtectionSet {
        Set<NodeId> allProtected = new HashSet<>(protectedNodeIds);
        allProtected.addAll(unfinishedToolNodeIds);
        allProtected.addAll(recoveryNodeIds);
        allProtected.addAll(evidenceNodeIds);
        protectedNodeIds = Set.copyOf(allProtected);
        boundaryNodeIds = Set.copyOf(boundaryNodeIds);
        unfinishedToolNodeIds = Set.copyOf(unfinishedToolNodeIds);
        recoveryNodeIds = Set.copyOf(recoveryNodeIds);
        evidenceNodeIds = Set.copyOf(evidenceNodeIds);
        requestFingerprints = Map.copyOf(requestFingerprints);
    }

    public static ProtectionSet empty() {
        return new ProtectionSet(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    public static ProtectionSet derive(List<ContextNode> sources, ProtectionSet host, int recentToolBatches) {
        Set<NodeId> pinned = new HashSet<>(host.protectedNodeIds());
        Set<NodeId> boundaries = new HashSet<>(host.boundaryNodeIds());
        Set<NodeId> unfinished = new HashSet<>(host.unfinishedToolNodeIds());
        Set<NodeId> recovery = new HashSet<>(host.recoveryNodeIds());
        Set<NodeId> evidence = new HashSet<>(host.evidenceNodeIds());
        Map<String, String> fingerprints = new TreeMap<>(host.requestFingerprints());
        for (ContextNode node : sources) {
            NodeId id = node.id();
            if (node.sourceKind() == SourceKind.REAL_USER) boundaries.add(id);
            if (node.sourceKind() == SourceKind.RECOVERY_CONTROL) recovery.add(id);
            if (node.sourceKind() == SourceKind.REVIEW_EVIDENCE) evidence.add(id);
            if (id.kind() == NodeId.Kind.REQUEST) {
                pinned.add(id);
                fingerprints.put(ContextDigests.canonicalDigest(id), ContextDigests.sourceDigest(List.of(node)));
            }
            if ("system".equals(node.message().role()) || switch (node.sourceKind()) {
                case SYSTEM, PERMISSION, PROVIDER_OPAQUE, LEGACY_UNKNOWN -> true;
                default -> false;
            } || hasOpaquePart(node)) pinned.add(id);
        }
        pinned.addAll(recovery);
        pinned.addAll(evidence);

        int recentRemaining = Math.max(0, recentToolBatches);
        boolean newestFailureFound = false;
        List<ContextToolBatch> batches = ContextToolBatch.scan(sources);
        for (int index = batches.size() - 1; index >= 0; index--) {
            ContextToolBatch batch = batches.get(index);
            List<NodeId> ids = batch.nodes().stream().map(ContextNode::id).toList();
            if (!batch.complete()) {
                unfinished.addAll(ids);
                continue;
            }
            if (recentRemaining > 0) {
                pinned.addAll(ids);
                recentRemaining--;
            }
            if (!newestFailureFound && batch.nodes().stream().anyMatch(ProtectionSet::knownFailure)) {
                newestFailureFound = true;
                pinned.addAll(ids);
            }
            if (hasUnknownSideEffect(batch) || ids.stream().anyMatch(pinned::contains)) pinned.addAll(ids);
        }
        return new ProtectionSet(pinned, boundaries, unfinished, recovery, evidence, fingerprints);
    }

    private static boolean knownFailure(ContextNode node) {
        var descriptor = node.descriptor();
        return descriptor != null && switch (descriptor.processOutcome()) {
            case FAILED, TIMED_OUT, CANCELLED, DENIED -> true;
            default -> false;
        };
    }

    private static boolean hasUnknownSideEffect(ContextToolBatch batch) {
        var calls = batch.nodes().get(0).message().toolCalls();
        for (ContextNode result : batch.nodes().subList(1, batch.nodes().size())) {
            var descriptor = result.descriptor();
            if (descriptor != null && descriptor.observationUnknown()) return true;
            var call = calls.stream().filter(candidate -> candidate.id().equals(result.message().toolCallId()))
                    .findFirst().orElseThrow();
            if ((descriptor == null || descriptor.processOutcome() == ProcessOutcome.UNKNOWN)
                    && !READ_ONLY.contains(java.util.Objects.toString(call.function().name(), ""))) return true;
        }
        return false;
    }

    private static boolean hasOpaquePart(ContextNode node) {
        return node.message().hasContentParts()
                && node.message().contentParts().stream().anyMatch(part -> part == null || !part.isText());
    }

    public String digest() {
        return ContextDigests.canonicalDigest(List.of(sorted(protectedNodeIds), sorted(boundaryNodeIds),
                sorted(unfinishedToolNodeIds), sorted(recoveryNodeIds), sorted(evidenceNodeIds), requestFingerprints));
    }

    private static List<NodeId> sorted(Set<NodeId> ids) {
        return ids.stream().sorted(Comparator.comparing(ContextDigests::canonicalDigest)).toList();
    }
}
