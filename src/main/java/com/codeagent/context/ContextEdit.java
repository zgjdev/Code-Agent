package com.codeagent.context;

import java.util.List;
import java.util.Objects;

public record ContextEdit(String editId, Layer layer, Kind kind, List<NodeId> orderedSourceIds,
                          String sourceDigest, List<ContextNode> replacementNodes,
                          List<String> retiredEditIds, List<Dependency> dependencies, int algorithmVersion) {
    public enum Layer { SNIP, MICRO, COLLAPSE, AUTO }
    public enum Kind { REMOVE_SPAN, REPLACE_BODY, REPLACE_SPAN }
    public record Dependency(NodeId nodeId, String contentDigest, boolean requiresFullBody) {
        public Dependency {
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(contentDigest);
            if (nodeId.kind() == NodeId.Kind.REQUEST) {
                throw new IllegalArgumentException("Request-only dependency");
            }
        }
    }

    public ContextEdit {
        if (editId == null || editId.isBlank() || algorithmVersion < 1) {
            throw new IllegalArgumentException("Invalid context edit metadata");
        }
        Objects.requireNonNull(layer);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(sourceDigest);
        orderedSourceIds = List.copyOf(orderedSourceIds);
        replacementNodes = List.copyOf(replacementNodes);
        retiredEditIds = List.copyOf(retiredEditIds);
        dependencies = List.copyOf(dependencies);
        if (orderedSourceIds.isEmpty() || orderedSourceIds.stream().anyMatch(id -> id.kind() != NodeId.Kind.RAW)) {
            throw new IllegalArgumentException("Context edits require raw sources");
        }
    }
}
