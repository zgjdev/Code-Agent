package com.codeagent.context;

import com.codeagent.context.*;
import java.util.*;

/** Deterministic edit validation. This class never changes raw messages or persists runtime protection. */
public final class ContextEditReplay {
    private ContextEditReplay() {}
    public static ContextScopeState apply(ContextScopeState state, ContextEditBatch batch, ProtectionSet protection) {
        if (!state.scopeId().equals(batch.scopeId()) || state.sourceRevision() != batch.expectedSourceRevision()
                || state.contextGeneration() != batch.expectedContextGeneration()
                || !baseDigest(state).equals(batch.baseDigest())
                || protection != null && !protection.digest().equals(batch.expectedProtectionDigest()))
            throw new StaleContextException();
        Map<String, ContextEdit> active = new LinkedHashMap<>();
        state.activeEdits().forEach(edit -> active.put(edit.editId(), edit));
        Set<String> newIds = new HashSet<>();
        Set<String> retirements = new HashSet<>();
        for (ContextEdit edit : batch.edits()) {
            require(newIds.add(edit.editId()) && !active.containsKey(edit.editId()), "Duplicate edit identity");
            List<ContextNode> members = members(state, edit);
            require(ContextDigests.sourceDigest(members).equals(edit.sourceDigest()), "Source digest mismatch");
            if (protection != null) {
                require(members.stream().noneMatch(n -> protection.protectedNodeIds().contains(n.id())), "Protected edit source");
                require(members.stream().skip(1).noneMatch(n -> protection.boundaryNodeIds().contains(n.id())), "Edit crosses boundary");
            }
            Set<String> ids = new HashSet<>(edit.retiredEditIds());
            require(ids.size() == edit.retiredEditIds().size(), "Duplicate retirement");
            for (String id : ids) {
                require(active.containsKey(id), "Unknown retirement");
                require(retirements.add(id), "Repeated retirement");
            }
            Set<NodeId> sources = new HashSet<>(edit.orderedSourceIds());
            for (ContextEdit old : state.activeEdits()) {
                if (old.orderedSourceIds().stream().anyMatch(sources::contains)) {
                    require(sources.containsAll(old.orderedSourceIds()) && ids.contains(old.editId()),
                            "Overlapping edit must fully contain and retire predecessor");
                }
            }
            if (edit.kind() == ContextEdit.Kind.REMOVE_SPAN) require(edit.replacementNodes().isEmpty(), "Remove has replacements");
            else require(!edit.replacementNodes().isEmpty(), "Replacement is empty");
            if (edit.kind() == ContextEdit.Kind.REPLACE_BODY) {
                require(members.size() == 1 && edit.replacementNodes().size() == 1, "Body edit cardinality");
                var before = members.get(0); var after = edit.replacementNodes().get(0);
                require(Objects.equals(before.message().role(), after.message().role())
                        && Objects.equals(before.message().toolCallId(), after.message().toolCallId())
                        && Objects.equals(before.message().toolCalls(), after.message().toolCalls())
                        && Objects.equals(before.descriptor(), after.descriptor())
                        && Objects.equals(before.executionId(), after.executionId())
                        && Objects.equals(before.taskAttemptId(), after.taskAttemptId())
                        && Objects.equals(before.toolBatchId(), after.toolBatchId()), "Body edit changes protocol or facts");
            }
            for (int i = 0; i < edit.replacementNodes().size(); i++) {
                require(edit.replacementNodes().get(i).id().equals(NodeId.edit(state.scopeId(), edit.editId(), i)),
                        "Invalid replacement identity");
            }
        }
        retirements.forEach(active::remove);
        batch.edits().forEach(edit -> active.put(edit.editId(), edit));
        Set<NodeId> covered = new HashSet<>();
        for (ContextEdit edit : active.values())
            for (NodeId id : edit.orderedSourceIds()) require(covered.add(id), "Overlapping active edits");
        ContextScopeState next = new ContextScopeState(state.scopeId(), state.sourceRevision(),
                state.contextGeneration() + 1, state.sourceNodes(), List.copyOf(active.values()));
        List<ContextNode> visible = overlay(next, active.keySet());
        Map<NodeId, ContextNode> visibleById = new HashMap<>();
        visible.forEach(node -> require(visibleById.put(node.id(), node) == null, "Duplicate projected identity"));
        for (ContextEdit edit : active.values()) for (ContextEdit.Dependency dep : edit.dependencies()) {
            ContextNode node = visibleById.get(dep.nodeId());
            require(node != null && ContextDigests.sourceDigest(List.of(node)).equals(dep.contentDigest()), "Unreachable or changed dependency");
            if (dep.requiresFullBody()) require(dep.nodeId().kind() == NodeId.Kind.RAW, "Full body dependency must reference raw source");
        }
        for (String id : active.keySet()) visit(id, active, new HashSet<>(), new HashSet<>());
        // Valid groups may not become malformed; existing malformed spans must remain unchanged.
        Set<NodeId> originalMalformed = new HashSet<>();
        ContextToolBatch.scan(state.sourceNodes()).stream().filter(b -> !b.complete())
                .forEach(b -> b.nodes().forEach(n -> originalMalformed.add(n.id())));
        require(covered.stream().noneMatch(originalMalformed::contains), "Edit intersects malformed tool group");
        for (var group : ContextToolBatch.scan(visible)) if (!group.complete())
            require(group.nodes().stream().allMatch(n -> originalMalformed.contains(n.id())), "Edit breaks tool pairing");
        return next;
    }
    public static List<ContextNode> project(ContextScopeState state, ProtectionSet protection) {
        Set<String> enabled = new LinkedHashSet<>();
        for (ContextEdit edit : state.activeEdits()) if (edit.orderedSourceIds().stream()
                .noneMatch(protection.protectedNodeIds()::contains)) enabled.add(edit.editId());
        boolean changed;
        do {
            changed = false;
            Set<NodeId> visible = new HashSet<>();
            overlay(state, enabled).forEach(n -> visible.add(n.id()));
            for (ContextEdit edit : state.activeEdits()) if (enabled.contains(edit.editId())
                    && edit.dependencies().stream().anyMatch(d -> !visible.contains(d.nodeId()))) {
                enabled.remove(edit.editId()); changed = true;
            }
        } while (changed);
        return overlay(state, enabled);
    }

    private static List<ContextNode> members(ContextScopeState state, ContextEdit edit) {
        List<NodeId> ids = edit.orderedSourceIds();
        int start = -1;
        for (int i = 0; i < state.sourceNodes().size(); i++) if (state.sourceNodes().get(i).id().equals(ids.get(0))) { start = i; break; }
        require(start >= 0 && start + ids.size() <= state.sourceNodes().size(), "Missing edit source");
        var members = state.sourceNodes().subList(start, start + ids.size());
        require(members.stream().map(ContextNode::id).toList().equals(ids), "Sources are not an ordered contiguous span");
        return members;
    }

    private static List<ContextNode> overlay(ContextScopeState state, Set<String> enabled) {
        Map<NodeId, ContextEdit> starts = new HashMap<>();
        for (ContextEdit edit : state.activeEdits()) if (enabled.contains(edit.editId())) starts.put(edit.orderedSourceIds().get(0), edit);
        List<ContextNode> result = new ArrayList<>();
        for (int i = 0; i < state.sourceNodes().size();) {
            ContextNode node = state.sourceNodes().get(i); ContextEdit edit = starts.get(node.id());
            if (edit == null) { result.add(node); i++; }
            else { result.addAll(edit.replacementNodes()); i += edit.orderedSourceIds().size(); }
        }
        return List.copyOf(result);
    }

    private static void visit(String id, Map<String, ContextEdit> edits, Set<String> stack, Set<String> done) {
        if (done.contains(id)) return;
        require(stack.add(id), "Cyclic edit dependency");
        for (var dependency : edits.get(id).dependencies()) if (dependency.nodeId().kind() == NodeId.Kind.EDIT) {
            String target = dependency.nodeId().editId();
            require(edits.containsKey(target), "Missing edit dependency"); visit(target, edits, stack, done);
        }
        stack.remove(id); done.add(id);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    public static String baseDigest(ContextScopeState state) {
        return ContextDigests.sourceDigest(state.sourceNodes().stream().filter(n -> n.id().kind() == NodeId.Kind.RAW).toList());
    }
    public static final class StaleContextException extends IllegalStateException {
        public StaleContextException() { super("Context candidate is stale"); }
    }
}
