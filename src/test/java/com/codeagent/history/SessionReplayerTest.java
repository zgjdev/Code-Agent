package com.codeagent.history;

import com.codeagent.llm.LlmClient;
import com.codeagent.context.MeasuredUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionReplayerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION_ID = "session-test";
    private final SessionReplayer replayer = new SessionReplayer();

    @Test
    void appendsMessagesAndReplacesCurrentSystemNode() {
        SessionProjection projection = replay(
                message(0, SessionEvent.Types.SYSTEM_MESSAGE,
                        LlmClient.Message.system("system-v1"), SessionEvent.SurfaceOperation.append()),
                message(1, SessionEvent.Types.USER_MESSAGE,
                        LlmClient.Message.user("hello"), SessionEvent.SurfaceOperation.append()),
                message(2, SessionEvent.Types.SYSTEM_MESSAGE,
                        LlmClient.Message.system("system-v2"), SessionEvent.SurfaceOperation.replace(0, 0)));

        assertEquals(List.of("system-v2", "hello"),
                projection.messages().stream().map(LlmClient.Message::content).toList());
        assertEquals(3, projection.historyVersion());
    }

    @Test
    void clearDropsVisibleMessagesButKeepsLaterMessages() {
        SessionProjection projection = replay(
                message(0, SessionEvent.Types.SYSTEM_MESSAGE,
                        LlmClient.Message.system("system"), SessionEvent.SurfaceOperation.append()),
                message(1, SessionEvent.Types.USER_MESSAGE,
                        LlmClient.Message.user("old"), SessionEvent.SurfaceOperation.append()),
                event(2, SessionEvent.Types.SURFACE_CLEAR, SessionEvent.SurfaceOperation.clear(), JSON.createObjectNode()),
                message(3, SessionEvent.Types.SYSTEM_MESSAGE,
                        LlmClient.Message.system("fresh"), SessionEvent.SurfaceOperation.append()));

        assertEquals(List.of("fresh"),
                projection.messages().stream().map(LlmClient.Message::content).toList());
    }

    @Test
    void completedCompactionAtomicallyReplacesRange() {
        SessionProjection projection = replay(
                message(0, SessionEvent.Types.SYSTEM_MESSAGE,
                        LlmClient.Message.system("system"), SessionEvent.SurfaceOperation.append()),
                message(1, SessionEvent.Types.USER_MESSAGE,
                        LlmClient.Message.user("old-user"), SessionEvent.SurfaceOperation.append()),
                message(2, SessionEvent.Types.ASSISTANT_MESSAGE,
                        LlmClient.Message.assistant("old-answer"), SessionEvent.SurfaceOperation.append()),
                compactionEvent(3, SessionEvent.Types.COMPACTION_START, "compact-1", null),
                compactionMessage(4, "compact-1", LlmClient.Message.user("summary"),
                        SessionEvent.SurfaceOperation.replace(1, 2)),
                compactionEvent(5, SessionEvent.Types.COMPACTION_END, "compact-1", "completed"));

        assertEquals(List.of("system", "summary"),
                projection.messages().stream().map(LlmClient.Message::content).toList());
        assertEquals(1, projection.compactionGeneration());
    }

    @Test
    void incompleteCompactionDoesNotChangeSurface() {
        SessionProjection projection = replay(
                message(0, SessionEvent.Types.SYSTEM_MESSAGE,
                        LlmClient.Message.system("system"), SessionEvent.SurfaceOperation.append()),
                message(1, SessionEvent.Types.USER_MESSAGE,
                        LlmClient.Message.user("old"), SessionEvent.SurfaceOperation.append()),
                compactionEvent(2, SessionEvent.Types.COMPACTION_START, "compact-1", null),
                compactionMessage(3, "compact-1", LlmClient.Message.user("summary"),
                        SessionEvent.SurfaceOperation.replace(1, 1)));

        assertEquals(List.of("system", "old"),
                projection.messages().stream().map(LlmClient.Message::content).toList());
        assertTrue(projection.warnings().stream().anyMatch(value -> value.contains("compact-1")));
    }

    @Test
    void tracksIncompleteRequestsAndPendingTools() {
        ObjectNode request = JSON.createObjectNode().put("requestId", "request-1");
        ObjectNode tool = JSON.createObjectNode().put("invocationId", "call-1").put("name", "write_file");

        SessionProjection projection = replay(
                event(0, SessionEvent.Types.REQUEST_STARTED, SessionEvent.SurfaceOperation.none(), request),
                event(1, SessionEvent.Types.TOOL_CALL, SessionEvent.SurfaceOperation.none(), tool));

        assertTrue(projection.incompleteRequestIds().contains("request-1"));
        assertTrue(projection.pendingTools().containsKey("call-1"));
        assertFalse(projection.cleanlyClosed());
    }

    @Test
    void commitsAssistantAndUsageOnlyAfterRequestFinishes() {
        ObjectNode started = JSON.createObjectNode().put("requestId", "request-1");
        ObjectNode assistant = JSON.createObjectNode().put("requestId", "request-1");
        assistant.set("message", JSON.valueToTree(LlmClient.Message.assistant("answer")));
        ObjectNode usage = JSON.createObjectNode()
                .put("requestId", "request-1")
                .put("provider", "deepseek")
                .put("model", "deepseek-chat");
        ObjectNode measured = usage.putObject("usage");
        measured.put("inputTokens", 100).put("outputTokens", 20).put("cachedInputTokens", 0)
                .put("inputScope", MeasuredUsage.InputScope.TOTAL_PROMPT.name())
                .put("includesTools", true).put("includesSystem", true).put("trusted", true)
                .put("measuredAtEpochMilli", 1_767_225_600_000L);

        SessionProjection incomplete = replay(
                event(0, SessionEvent.Types.REQUEST_STARTED, SessionEvent.SurfaceOperation.none(), started),
                event(1, SessionEvent.Types.ASSISTANT_MESSAGE, SessionEvent.SurfaceOperation.append(), assistant),
                event(2, SessionEvent.Types.PROVIDER_USAGE, SessionEvent.SurfaceOperation.none(), usage));
        assertTrue(incomplete.messages().isEmpty());
        assertEquals(null, incomplete.lastCompletedUsage());

        ObjectNode finished = JSON.createObjectNode().put("requestId", "request-1");
        SessionProjection complete = replay(
                event(0, SessionEvent.Types.REQUEST_STARTED, SessionEvent.SurfaceOperation.none(), started),
                event(1, SessionEvent.Types.ASSISTANT_MESSAGE, SessionEvent.SurfaceOperation.append(), assistant),
                event(2, SessionEvent.Types.PROVIDER_USAGE, SessionEvent.SurfaceOperation.none(), usage),
                event(3, SessionEvent.Types.REQUEST_FINISHED, SessionEvent.SurfaceOperation.none(), finished));
        assertEquals(List.of("answer"),
                complete.messages().stream().map(LlmClient.Message::content).toList());
        assertEquals(120, complete.lastCompletedUsage().usage().usageAnchorTokens());
    }

    @Test
    void skipsUnknownIgnorableEventButRejectsUnknownRequiredEvent() {
        SessionProjection projection = replay(new SessionEvent(
                SessionEvent.CURRENT_SCHEMA_VERSION, SESSION_ID, 0, 1,
                "future/diagnostic", "react", "agent", "test", true,
                SessionEvent.SurfaceOperation.none(), JSON.createObjectNode()));
        assertEquals(0, projection.lastAppliedSequence());

        assertThrows(SessionReplayer.UnsupportedSessionEventException.class, () -> replay(new SessionEvent(
                SessionEvent.CURRENT_SCHEMA_VERSION, SESSION_ID, 0, 1,
                "future/semantic", "react", "agent", "test", false,
                SessionEvent.SurfaceOperation.none(), JSON.createObjectNode())));
    }

    @Test
    void rejectsSequenceGapsAndEventsFromAnotherSession() {
        assertThrows(SessionReplayer.CorruptSessionException.class, () -> replay(
                event(1, SessionEvent.Types.SESSION_START,
                        SessionEvent.SurfaceOperation.none(), JSON.createObjectNode())));

        SessionEvent foreign = new SessionEvent(
                SessionEvent.CURRENT_SCHEMA_VERSION, "another-session", 0, 1,
                SessionEvent.Types.SESSION_START, "react", "agent", "test", false,
                SessionEvent.SurfaceOperation.none(), JSON.createObjectNode());
        assertThrows(SessionReplayer.CorruptSessionException.class, () -> replay(foreign));
    }

    private SessionProjection replay(SessionEvent... events) {
        return replayer.replay(manifest(), List.of(events));
    }

    private SessionManifest manifest() {
        return new SessionManifest(SessionEvent.CURRENT_SCHEMA_VERSION, SESSION_ID, "C:\\workspace",
                null, null, 1, 1, null, false, -1, false, null);
    }

    private SessionEvent message(long sequence, String type, LlmClient.Message message,
                                 SessionEvent.SurfaceOperation operation) {
        ObjectNode payload = JSON.createObjectNode();
        payload.set("message", JSON.valueToTree(message));
        return event(sequence, type, operation, payload);
    }

    private SessionEvent compactionMessage(long sequence, String compactionId,
                                           LlmClient.Message message,
                                           SessionEvent.SurfaceOperation operation) {
        ObjectNode payload = JSON.createObjectNode().put("compactionId", compactionId);
        payload.set("message", JSON.valueToTree(message));
        return event(sequence, SessionEvent.Types.USER_MESSAGE, operation, payload);
    }

    private SessionEvent compactionEvent(long sequence, String type, String id, String status) {
        ObjectNode payload = JSON.createObjectNode().put("compactionId", id);
        if (status != null) {
            payload.put("status", status);
        }
        return event(sequence, type, SessionEvent.SurfaceOperation.none(), payload);
    }

    private SessionEvent event(long sequence, String type,
                               SessionEvent.SurfaceOperation operation, ObjectNode payload) {
        return new SessionEvent(SessionEvent.CURRENT_SCHEMA_VERSION, SESSION_ID, sequence, sequence + 1,
                type, "react", "agent", "test", false, operation, payload);
    }
}
