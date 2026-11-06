package com.codeagent.history;

import com.codeagent.context.ContextDigests;
import com.codeagent.context.ContextEdit;
import com.codeagent.context.ContextEditBatch;
import com.codeagent.context.ContextEditReplay;
import com.codeagent.context.ProtectionSet;
import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCheckpointStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    void v3CheckpointPreservesContextStateAndOperationIdempotenceThroughTailReplay() throws Exception {
        var manifest = manifest("session-context-checkpoint");
        var replayer = new SessionReplayer();
        var source = message(manifest.sessionId(), 0, "legacy body");
        var before = replayer.replay(manifest, List.of(source));
        var scope = before.contextScopes().get(manifest.sessionId());
        var edit = new ContextEdit("edit", ContextEdit.Layer.SNIP, ContextEdit.Kind.REMOVE_SPAN,
                List.of(scope.sourceNodes().get(0).id()), ContextDigests.sourceDigest(scope.sourceNodes()),
                List.of(), List.of(), List.of(), 1);
        var batch = new ContextEditBatch("operation", scope.scopeId(), scope.sourceRevision(),
                scope.contextGeneration(), ProtectionSet.empty().digest(), ContextEditReplay.baseDigest(scope),
                List.of(edit));
        var committed = contextEdit(manifest.sessionId(), 1, batch);
        var events = List.of(source, committed, contextEdit(manifest.sessionId(), 2, batch),
                message(manifest.sessionId(), 3, "tail"));
        var checkpoints = new SessionCheckpointStore(tempDir);
        var prefix = events.subList(0, 2);
        checkpoints.write(manifest.sessionId(), prefix, replayer.replay(manifest, prefix));

        var loaded = checkpoints.loadLatest(manifest, events);
        var file = loaded.checkpoint().orElseThrow().path();
        assertEquals(replayer.replay(manifest, events), loaded.projection());
        var stored = (ObjectNode) JSON.readTree(file.toFile());
        assertEquals(3, stored.path("schemaVersion").asInt());
        assertTrue(loaded.projection().committedOperationDigests().containsKey("operation"));

        stored.put("schemaVersion", 2);
        JSON.writeValue(file.toFile(), stored);
        var fallback = checkpoints.loadLatest(manifest, events);
        assertFalse(fallback.checkpoint().isPresent());
        assertEquals(replayer.replay(manifest, events), fallback.projection());
    }

    @Test
    void legacyCheckpointWithoutScopeIndexReplaysPrefixToRebuildMembership() throws Exception {
        var manifest = manifest("legacy-scope-checkpoint");
        var events = List.of(message(manifest.sessionId(), 0, "old body"));
        var replayer = new SessionReplayer();
        var expected = replayer.replay(manifest, events);
        var checkpoints = new SessionCheckpointStore(tempDir);
        checkpoints.write(manifest.sessionId(), events, expected);
        var file = checkpoints.loadLatest(manifest, events).checkpoint().orElseThrow().path();
        var stored = (ObjectNode) JSON.readTree(file.toFile());
        assertEquals(2, stored.path("schemaVersion").asInt());
        ((ObjectNode) stored.path("projection")).remove("contextScopes");
        JSON.writeValue(file.toFile(), stored);

        var loaded = checkpoints.loadLatest(manifest, events);
        assertFalse(loaded.checkpoint().isPresent());
        assertEquals(expected, loaded.projection());
    }

    private static SessionEvent contextEdit(String sessionId, long sequence, ContextEditBatch batch) {
        return new SessionEvent(3, sessionId, sequence, sequence + 1, SessionEvent.Types.CONTEXT_EDIT,
                "react", "agent", "test", false, SessionEvent.SurfaceOperation.none(), JSON.valueToTree(batch));
    }

    @Test
    void checkpointAndTailEqualFullReplay() throws Exception {
        SessionManifest manifest = manifest("session-checkpoint");
        List<SessionEvent> events = List.of(message(manifest.sessionId(), 0, "one"),
                message(manifest.sessionId(), 1, "two"), message(manifest.sessionId(), 2, "three"));
        SessionReplayer replayer = new SessionReplayer();
        SessionProjection prefix = replayer.replay(manifest, events.subList(0, 2));
        SessionCheckpointStore checkpoints = new SessionCheckpointStore(tempDir);

        checkpoints.write(manifest.sessionId(), events.subList(0, 2), prefix);
        SessionCheckpointStore.LoadResult loaded = checkpoints.loadLatest(manifest, events);

        assertTrue(loaded.checkpoint().isPresent());
        assertEquals(replayer.replay(manifest, events).messages(), loaded.projection().messages());
    }

    @Test
    void checkpointRoundTripsTopLevelConversationAndOpenPlanTurn() throws Exception {
        SessionManifest manifest = manifest("session-conversation-checkpoint");
        List<SessionEvent> events = List.of(
                planTurn(manifest.sessionId(), 0, SessionEvent.Types.TURN_START,
                        "turn-1", "plan-1"),
                semanticPlanMessage(manifest.sessionId(), 1,
                        SessionEvent.Types.USER_MESSAGE, "user", "goal"),
                semanticPlanMessage(manifest.sessionId(), 2,
                        SessionEvent.Types.ASSISTANT_MESSAGE, "assistant", "done"),
                planTurn(manifest.sessionId(), 3, SessionEvent.Types.TURN_END,
                        "turn-1", "plan-1"));
        SessionReplayer replayer = new SessionReplayer();
        SessionProjection prefix = replayer.replay(manifest, events.subList(0, 2));
        SessionCheckpointStore checkpoints = new SessionCheckpointStore(tempDir);

        checkpoints.write(manifest.sessionId(), events.subList(0, 2), prefix);
        SessionCheckpointStore.LoadResult loaded = checkpoints.loadLatest(manifest, events);

        assertTrue(loaded.checkpoint().isPresent());
        assertTrue(loaded.projection().openTurns().isEmpty());
        assertEquals(List.of("goal", "done"),
                loaded.projection().conversationMessages().stream()
                        .map(LlmClient.Message::content).toList());
    }

    @Test
    void invalidPrefixHashFallsBackToFullReplay() throws Exception {
        SessionManifest manifest = manifest("session-corrupt-checkpoint");
        List<SessionEvent> original = List.of(message(manifest.sessionId(), 0, "one"));
        SessionCheckpointStore checkpoints = new SessionCheckpointStore(tempDir);
        checkpoints.write(manifest.sessionId(), original,
                new SessionReplayer().replay(manifest, original));
        List<SessionEvent> changed = List.of(message(manifest.sessionId(), 0, "changed"));

        SessionCheckpointStore.LoadResult loaded = checkpoints.loadLatest(manifest, changed);

        assertFalse(loaded.checkpoint().isPresent());
        assertEquals(List.of("changed"), loaded.projection().messages().stream()
                .map(LlmClient.Message::content).toList());
    }

    @Test
    void sessionStoreWritesCheckpointAfterCompletedCompactionAndClose() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(new SessionStore.SessionCreateRequest(
                     workspace, "deepseek", "model", null, "react", "agent"))) {
            handle.append(draft(SessionEvent.Types.USER_MESSAGE, messagePayload("old"),
                    SessionEvent.SurfaceOperation.append()));
            String id = "compact-1";
            handle.append(draft(SessionEvent.Types.COMPACTION_START,
                    JSON.createObjectNode().put("compactionId", id), SessionEvent.SurfaceOperation.none()));
            ObjectNode replacement = messagePayload("summary").put("compactionId", id);
            handle.append(draft(SessionEvent.Types.USER_MESSAGE, replacement,
                    SessionEvent.SurfaceOperation.replace(1, 1)));
            handle.append(draft(SessionEvent.Types.COMPACTION_END,
                    JSON.createObjectNode().put("compactionId", id).put("status", "completed"),
                    SessionEvent.SurfaceOperation.none()));
            assertTrue(Files.list(handle.sessionDirectory().resolve("checkpoints")).findAny().isPresent());
            handle.markClosed("test");
            assertTrue(Files.exists(handle.sessionDirectory().resolve("checkpoints")
                    .resolve("checkpoint-5.json")));
        }
    }

    @Test
    void periodicCheckpointSkipsAnOpenCompactionTransaction() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace-open-compaction"));
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(new SessionStore.SessionCreateRequest(
                     workspace, "deepseek", "model", null, "react", "agent"))) {
            for (int index = 0; index < 48; index++) {
                handle.append(draft(SessionEvent.Types.LEGACY_EVENT,
                        JSON.createObjectNode().put("index", index), SessionEvent.SurfaceOperation.none()));
            }
            handle.append(draft(SessionEvent.Types.COMPACTION_START,
                    JSON.createObjectNode().put("compactionId", "open"),
                    SessionEvent.SurfaceOperation.none()));

            assertFalse(Files.exists(handle.sessionDirectory().resolve("checkpoints")
                    .resolve("checkpoint-49.json")));
        }
    }

    private SessionEvent semanticPlanMessage(String sessionId, long sequence,
                                             String type, String role, String content) {
        ObjectNode payload = JSON.createObjectNode();
        LlmClient.Message message = "assistant".equals(role)
                ? LlmClient.Message.assistant(content)
                : LlmClient.Message.user(content);
        payload.set("message", JSON.valueToTree(message));
        payload.putObject("conversation")
                .put("turnId", "turn-1")
                .put("planId", "plan-1")
                .put("mode", "plan")
                .put("role", role)
                .put("content", content);
        return new SessionEvent(2, sessionId, sequence, sequence + 1, type,
                "plan", "plan-agent", "test", false,
                SessionEvent.SurfaceOperation.append(), payload);
    }

    private SessionEvent planTurn(String sessionId, long sequence, String type,
                                  String turnId, String planId) {
        ObjectNode payload = JSON.createObjectNode()
                .put("turnId", turnId)
                .put("planId", planId)
                .put("mode", "plan");
        return new SessionEvent(2, sessionId, sequence, sequence + 1, type,
                "plan", "plan-agent", "test", false,
                SessionEvent.SurfaceOperation.none(), payload);
    }

    private SessionEventDraft draft(String type, ObjectNode payload, SessionEvent.SurfaceOperation surface) {
        return new SessionEventDraft(type, "react", "agent", "test", false, surface, payload);
    }

    private ObjectNode messagePayload(String content) {
        ObjectNode payload = JSON.createObjectNode();
        payload.set("message", JSON.valueToTree(LlmClient.Message.user(content)));
        return payload;
    }

    private SessionEvent message(String id, long sequence, String content) {
        return new SessionEvent(2, id, sequence, sequence + 1, SessionEvent.Types.USER_MESSAGE,
                "react", "agent", "test", false, SessionEvent.SurfaceOperation.append(),
                messagePayload(content));
    }

    private SessionManifest manifest(String id) {
        return new SessionManifest(2, id, tempDir.toString(), null, null,
                1, 1, null, "react", "agent", false, -1, false, null);
    }
}
