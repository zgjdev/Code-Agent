package com.codeagent.history;

import com.codeagent.context.*;
import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ContextDurableReplayTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;
    private SessionStore.SessionCreateRequest request() {
        return new SessionStore.SessionCreateRequest(directory, "test", "test", null, "react", "test");
    }
    private SessionEvent message(SessionStore.SessionHandle h, String text) throws Exception {
        ObjectNode payload = JSON.createObjectNode().put("sourceKind", SourceKind.ASSISTANT.name());
        payload.set("message", JSON.valueToTree(LlmClient.Message.assistant(text)));
        payload.putObject("conversation").put("role", "assistant").put("content", text).put("mode", "react");
        return h.append(new SessionEventDraft(SessionEvent.Types.ASSISTANT_MESSAGE, "react", "test", "test", false,
                SessionEvent.SurfaceOperation.append(), payload));
    }
    private ContextEditBatch batch(SessionStore.SessionHandle h, String op, String editId) {
        var scope = h.projection().contextScopes().get(h.sessionId());
        var nodes = scope.sourceNodes();
        var edit = new ContextEdit(editId, ContextEdit.Layer.COLLAPSE, ContextEdit.Kind.REPLACE_SPAN,
                nodes.stream().map(ContextNode::id).toList(), ContextDigests.sourceDigest(nodes),
                List.of(new ContextNode(NodeId.edit(h.sessionId(), editId, 0), LlmClient.Message.assistant("summary"),
                        SourceKind.LOCAL_SUMMARY, null, null, null, null)), List.of(), List.of(), 1);
        return new ContextEditBatch(op, h.sessionId(), scope.sourceRevision(), scope.contextGeneration(),
                ProtectionSet.empty().digest(), ContextDigests.sourceDigest(nodes), List.of(edit));
    }
    @Test void editSurvivesRestartWithoutChangingRawOrSemanticHistory() throws Exception {
        String id; SessionProjection before; ContextEditBatch batch;
        try (var store = SessionStore.open(directory); var h = store.create(request())) {
            id = h.sessionId(); message(h, "one"); message(h, "two");
            batch = batch(h, "op", "edit");
            assertEquals(ContextEditSink.CommitResult.APPLIED, h.commitContextEdit(batch, ProtectionSet::empty));
            before = h.projection();
            assertEquals(List.of("one", "two"), before.messages().stream().map(LlmClient.Message::content).toList());
            assertEquals(before.messages(), before.conversationMessages());
            assertEquals(List.of("summary"), ContextEditReplay.project(before.contextScopes().get(id), ProtectionSet.empty())
                    .stream().map(n -> n.message().content()).toList());
        }
        try (var store = SessionStore.open(directory); var h = store.resumeWritable(id, directory)) {
            assertEquals(before, h.projection());
            long size = Files.size(h.sessionDirectory().resolve("events.jsonl"));
            assertEquals(ContextEditSink.CommitResult.ALREADY_APPLIED, h.commitContextEdit(batch, ProtectionSet::empty));
            assertEquals(size, Files.size(h.sessionDirectory().resolve("events.jsonl")));
        }
    }
    @Test void clearRetiresEditsButKeepsOperationIdentity() throws Exception {
        try (var store = SessionStore.open(directory); var h = store.create(request())) {
            message(h, "one"); var batch = batch(h, "op", "edit");
            h.commitContextEdit(batch, ProtectionSet::empty);
            h.append(new SessionEventDraft(SessionEvent.Types.SURFACE_CLEAR, "react", "test", "test", false,
                    SessionEvent.SurfaceOperation.clear(), JSON.createObjectNode()));
            assertTrue(h.projection().contextScopes().get(h.sessionId()).activeEdits().isEmpty());
            assertEquals(ContextEditSink.CommitResult.ALREADY_APPLIED, h.commitContextEdit(batch, ProtectionSet::empty));
            message(h, "later");
            long size = Files.size(h.sessionDirectory().resolve("events.jsonl"));
            assertThrows(IllegalArgumentException.class, () -> h.commitContextEdit(batch(h, "op", "new"), ProtectionSet::empty));
            assertEquals(size, Files.size(h.sessionDirectory().resolve("events.jsonl")));
        }
    }
    @Test void currentProtectionIsCheckedBeforeAnyWrite() throws Exception {
        try (var store = SessionStore.open(directory); var h = store.create(request())) {
            var first = message(h, "one"); var batch = batch(h, "op", "edit");
            var protectedNow = new ProtectionSet(Set.of(NodeId.raw(h.sessionId(), first.sequence())), Set.of(), Set.of(), Set.of(), Set.of());
            long size = Files.size(h.sessionDirectory().resolve("events.jsonl"));
            assertEquals(ContextEditSink.CommitResult.STALE, h.commitContextEdit(batch, () -> protectedNow));
            assertEquals(size, Files.size(h.sessionDirectory().resolve("events.jsonl")));
            assertTrue(h.projection().committedOperationDigests().isEmpty());
        }
    }
    @Test void protectionSupplierRunsOutsideSessionMonitor() throws Exception {
        try (var store = SessionStore.open(directory); var h = store.create(request())) {
            message(h, "one");
            assertEquals(ContextEditSink.CommitResult.APPLIED, h.commitContextEdit(batch(h, "op", "edit"), () -> {
                assertFalse(Thread.holdsLock(h), "Protection lock must precede SessionHandle lock");
                return ProtectionSet.empty();
            }));
        }
    }
    private void compact(SessionStore.SessionHandle h, int count, String id, boolean complete) throws Exception {
        compact(h, count, id, complete, List.of());
    }
    private void compact(SessionStore.SessionHandle h, int count, String id, boolean complete, List<String> retired) throws Exception {
        var scope = h.projection().contextScopes().get(h.sessionId());
        var nodes = scope.sourceNodes().subList(0, count);
        var start = JSON.createObjectNode().put("compactionId", id).put("scopeId", h.sessionId())
                .put("expectedSourceRevision", scope.sourceRevision()).put("expectedContextGeneration", scope.contextGeneration())
                .put("expectedProtectionDigest", ProtectionSet.empty().digest()).put("sourceDigest", ContextDigests.sourceDigest(nodes));
        start.set("orderedSourceIds", JSON.valueToTree(nodes.stream().map(ContextNode::id).toList()));
        start.set("coveredConversationNodeIds", JSON.valueToTree(nodes.stream().map(n -> n.id().sourceSequence()).toList()));
        start.set("retiredEditIds", JSON.valueToTree(retired));
        var summary = JSON.createObjectNode().put("compactionId", id).put("sourceKind", SourceKind.GLOBAL_SUMMARY.name());
        summary.set("message", JSON.valueToTree(LlmClient.Message.assistant(id)));
        h.append(new SessionEventDraft(SessionEvent.Types.COMPACTION_START, "react", "test", "test", false,
                SessionEvent.SurfaceOperation.none(), start, 3));
        h.append(new SessionEventDraft(SessionEvent.Types.ASSISTANT_MESSAGE, "react", "test", "test", false,
                SessionEvent.SurfaceOperation.none(), summary, 3));
        if (complete) h.append(new SessionEventDraft(SessionEvent.Types.COMPACTION_END, "react", "test", "test", false,
                SessionEvent.SurfaceOperation.none(), JSON.createObjectNode().put("compactionId", id).put("status", "completed")
                    .put("transactionDigest", ContextDigests.canonicalDigest(List.of(start, summary))), 3));
    }
    @Test void parentCompactionRejectsPartialOverlapEvenWhenEditIsRetired() throws Exception {
        try (var store = SessionStore.open(directory); var h = store.create(request())) {
            message(h, "one"); message(h, "two");
            h.commitContextEdit(batch(h, "op", "edit"), ProtectionSet::empty);
            assertThrows(IllegalArgumentException.class, () -> compact(h, 1, "partial", true, List.of("edit")));
            assertEquals(2, h.projection().messages().size());
            assertEquals(1, h.projection().contextScopes().get(h.sessionId()).activeEdits().size());
        }
    }
    @Test void fullParentCompactionRequiresExplicitRetirement() throws Exception {
        try (var store = SessionStore.open(directory); var h = store.create(request())) {
            message(h, "one"); message(h, "two");
            h.commitContextEdit(batch(h, "op", "edit"), ProtectionSet::empty);
            assertThrows(IllegalArgumentException.class, () -> compact(h, 2, "missing-retire", true));
        }
    }
    @Test void fullParentCompactionRetiresEditAndRetainsIdempotencyIndex() throws Exception {
        try (var store = SessionStore.open(directory); var h = store.create(request())) {
            message(h, "one"); message(h, "two"); var original = batch(h, "op", "edit");
            h.commitContextEdit(original, ProtectionSet::empty);
            compact(h, 2, "summary", true, List.of("edit"));
            assertTrue(h.projection().contextScopes().get(h.sessionId()).activeEdits().isEmpty());
            assertEquals(ContextEditSink.CommitResult.ALREADY_APPLIED, h.commitContextEdit(original, ProtectionSet::empty));
        }
    }
    @Test void syntheticProvenanceSurvivesSourceProjection() throws Exception {
        try (var store = SessionStore.open(directory); var h = store.create(request())) {
            var synthetic = new ContextNode.SyntheticContext("lsp", 7, true);
            var payload = JSON.createObjectNode().put("sourceKind", SourceKind.SYNTHETIC.name());
            payload.set("message", JSON.valueToTree(LlmClient.Message.user("diagnostic")));
            payload.set("syntheticContext", JSON.valueToTree(synthetic));
            h.append(new SessionEventDraft(SessionEvent.Types.USER_MESSAGE, "react", "test", "test", false,
                    SessionEvent.SurfaceOperation.append(), payload));
            assertEquals(synthetic, h.projection().contextScopes().get(h.sessionId()).sourceNodes().get(0).syntheticContext());
        }
    }
    @Test void twoCompactionsReplaceExplicitMembersDespiteNonmonotonicSequences() throws Exception {
        String id;
        try (var store = SessionStore.open(directory); var h = store.create(request())) {
            id = h.sessionId(); message(h, "one"); message(h, "two"); message(h, "three"); message(h, "four");
            compact(h, 2, "summary-one", true);
            compact(h, 2, "summary-two", true);
            assertEquals(List.of("summary-two", "four"), h.projection().conversationMessages().stream().map(LlmClient.Message::content).toList());
            assertEquals(2, h.projection().compactionGeneration());
        }
        try (var store = SessionStore.open(directory); var h = store.resumeWritable(id, directory)) {
            assertEquals(List.of("summary-two", "four"), h.projection().messages().stream().map(LlmClient.Message::content).toList());
            assertEquals(2, h.projection().contextScopes().get(id).sourceNodes().size());
        }
    }
    @Test void missingEndPreservesSourcesAcrossRestartAndSuppressesCheckpoint() throws Exception {
        String id;
        try (var store = SessionStore.open(directory); var h = store.create(request())) {
            id = h.sessionId(); message(h, "one"); message(h, "two"); compact(h, 2, "incomplete", false);
            new SessionCheckpointStore(h.sessionDirectory()).write(id, h.readAll(), h.projection());
            try (var files = Files.list(h.sessionDirectory().resolve("checkpoints"))) { assertEquals(0, files.count()); }
        }
        try (var store = SessionStore.open(directory); var h = store.resumeWritable(id, directory)) {
            assertEquals(List.of("one", "two"), h.projection().messages().stream().map(LlmClient.Message::content).toList());
            assertEquals(0, h.projection().compactionGeneration());
        }
    }
}
