package com.codeagent.history;

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
