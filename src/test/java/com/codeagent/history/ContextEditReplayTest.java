package com.codeagent.history;

import com.codeagent.context.*;
import com.codeagent.context.ContextEditReplay;
import com.codeagent.context.ContextScopeState;
import com.codeagent.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ContextEditReplayTest {
    @Test void autoCanCoverHistoricalUserBoundariesWhileLocalCollapseCannot() {
        var first = new ContextNode(NodeId.raw("s", 1), LlmClient.Message.user("first"), SourceKind.REAL_USER, "e1", null, null, null);
        var second = new ContextNode(NodeId.raw("s", 2), LlmClient.Message.user("second"), SourceKind.REAL_USER, "e2", null, null, null);
        var source = new ContextScopeState("s", 2, 0, List.of(first, second), List.of());
        var protection = ProtectionSet.derive(source.sourceNodes(), ProtectionSet.empty(), 2);
        var local = edit("local", source.sourceNodes(), List.of(), List.of());
        var global = new ContextEdit("global", ContextEdit.Layer.AUTO, ContextEdit.Kind.REPLACE_SPAN,
                local.orderedSourceIds(), local.sourceDigest(),
                List.of(new ContextNode(NodeId.edit("s", "global", 0), LlmClient.Message.assistant("summary"),
                        SourceKind.GLOBAL_SUMMARY, null, null, null, null)), List.of(), List.of(), 1);
        var localBatch = new ContextEditBatch("local-op", "s", 2, 0, protection.digest(), ContextEditReplay.baseDigest(source), List.of(local));
        var globalBatch = new ContextEditBatch("global-op", "s", 2, 0, protection.digest(), ContextEditReplay.baseDigest(source), List.of(global));
        assertThrows(IllegalArgumentException.class, () -> ContextEditReplay.apply(source, localBatch, protection));
        assertEquals("global", ContextEditReplay.apply(source, globalBatch, protection).activeEdits().get(0).editId());
    }
    private ContextNode raw(long seq) { return new ContextNode(NodeId.raw("s", seq),
            LlmClient.Message.assistant("body-" + seq), SourceKind.ASSISTANT, null, null, null, null); }
    private ContextScopeState state() { return new ContextScopeState("s", 3, 0,
            List.of(raw(10), raw(2), raw(8)), List.of()); }
    private ContextEdit edit(String id, List<ContextNode> sources, List<String> retire,
                             List<ContextEdit.Dependency> dependencies) {
        return new ContextEdit(id, ContextEdit.Layer.COLLAPSE, ContextEdit.Kind.REPLACE_SPAN,
                sources.stream().map(ContextNode::id).toList(), ContextDigests.sourceDigest(sources),
                List.of(new ContextNode(NodeId.edit("s", id, 0), LlmClient.Message.assistant("summary"),
                        SourceKind.LOCAL_SUMMARY, null, null, null, null)), retire, dependencies, 1);
    }
    private ContextEditBatch batch(ContextScopeState state, ContextEdit... edits) {
        return new ContextEditBatch("op", "s", state.sourceRevision(), state.contextGeneration(),
                ProtectionSet.empty().digest(), ContextDigests.sourceDigest(state.sourceNodes()), List.of(edits));
    }
    @Test void replacesExplicitOrderedMembersEvenWhenSequenceIsNotMonotonic() {
        var state = state();
        var next = ContextEditReplay.apply(state, batch(state, edit("a", state.sourceNodes().subList(0, 2), List.of(), List.of())), ProtectionSet.empty());
        assertEquals(List.of("summary", "body-8"), ContextEditReplay.project(next, ProtectionSet.empty()).stream().map(n -> n.message().content()).toList());
        assertEquals(state.sourceNodes(), next.sourceNodes());
        assertEquals(1, next.contextGeneration());
    }
    @Test void rejectsNoncontiguousMembersAndLeavesStateUnchanged() {
        var state = state();
        assertThrows(IllegalArgumentException.class, () -> ContextEditReplay.apply(state,
                batch(state, edit("a", List.of(raw(10), raw(8)), List.of(), List.of())), ProtectionSet.empty()));
        assertTrue(state.activeEdits().isEmpty());
    }
    @Test void fullContainmentMustRetireOldEditAtomically() {
        var s = state();
        var first = ContextEditReplay.apply(s, batch(s, edit("a", s.sourceNodes().subList(0, 2), List.of(), List.of())), ProtectionSet.empty());
        assertThrows(IllegalArgumentException.class, () -> ContextEditReplay.apply(first,
                batch(first, edit("b", s.sourceNodes(), List.of(), List.of())), ProtectionSet.empty()));
        var second = ContextEditReplay.apply(first, batch(first, edit("b", s.sourceNodes(), List.of("a"), List.of())), ProtectionSet.empty());
        assertEquals(List.of("b"), second.activeEdits().stream().map(ContextEdit::editId).toList());
    }
    @Test void rejectsPartialOverlapEvenIfOldEditIsRetired() {
        var s = state();
        var first = ContextEditReplay.apply(s, batch(s, edit("a", s.sourceNodes().subList(0, 2), List.of(), List.of())), ProtectionSet.empty());
        assertThrows(IllegalArgumentException.class, () -> ContextEditReplay.apply(first,
                batch(first, edit("b", s.sourceNodes().subList(1, 3), List.of("a"), List.of())), ProtectionSet.empty()));
    }
    @Test void protectionChangeInvalidatesCandidateWithoutSourceRevisionChange() {
        var s = state();
        var protection = new ProtectionSet(Set.of(raw(10).id()), Set.of(), Set.of(), Set.of(), Set.of());
        assertThrows(ContextEditReplay.StaleContextException.class, () -> ContextEditReplay.apply(s,
                batch(s, edit("a", s.sourceNodes(), List.of(), List.of())), protection));
    }
    @Test void projectionBypassesEntireEditWhenSourceBecomesProtected() {
        var s = state();
        var next = ContextEditReplay.apply(s, batch(s, edit("a", s.sourceNodes(), List.of(), List.of())), ProtectionSet.empty());
        var protection = new ProtectionSet(Set.of(raw(2).id()), Set.of(), Set.of(), Set.of(), Set.of());
        assertEquals(s.sourceNodes(), ContextEditReplay.project(next, protection));
        assertEquals(1, next.activeEdits().size());
    }
    @Test void cannotHideFullBodyDependencyOfExistingEdit() {
        var s = state();
        var dep = new ContextEdit.Dependency(raw(8).id(), ContextDigests.sourceDigest(List.of(raw(8))), true);
        var first = ContextEditReplay.apply(s, batch(s, edit("a", s.sourceNodes().subList(0, 2), List.of(), List.of(dep))), ProtectionSet.empty());
        assertThrows(IllegalArgumentException.class, () -> ContextEditReplay.apply(first,
                batch(first, edit("b", List.of(raw(8)), List.of(), List.of())), ProtectionSet.empty()));
    }
}
