package com.codeagent.context;

import com.codeagent.context.ContextEdit;
import com.codeagent.context.ContextNode;
import java.util.List;

/** Immutable durable scope data; runtime options, protection and trackers are rebuilt by the owner. */
public record ContextScopeState(String scopeId, long sourceRevision, long contextGeneration,
                                List<ContextNode> sourceNodes, List<ContextEdit> activeEdits) {
    public ContextScopeState {
        if (scopeId == null || scopeId.isBlank() || sourceRevision < 0 || contextGeneration < 0)
            throw new IllegalArgumentException("Invalid context scope identity");
        sourceNodes = List.copyOf(sourceNodes);
        activeEdits = List.copyOf(activeEdits);
        var ids = new java.util.HashSet<NodeId>();
        for (ContextNode node : sourceNodes) {
            if (node.id().kind() == NodeId.Kind.EDIT || !ids.add(node.id())
                    || node.id().kind() == NodeId.Kind.RAW && !scopeId.equals(node.id().scopeId()))
                throw new IllegalArgumentException("Invalid context scope sources");
        }
    }
}
