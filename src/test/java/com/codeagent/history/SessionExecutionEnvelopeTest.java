package com.codeagent.history;

import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionExecutionEnvelopeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION_ID = "session-envelope";

    @Test
    void indexesCompleteExecutionEnvelope() {
        SessionProjection projection = new SessionReplayer().replay(manifest(), List.of(
                event(0, SessionEvent.Types.EXECUTION_START, startPayload()),
                message(1, SessionEvent.Types.USER_MESSAGE, "user", "goal"),
                message(2, SessionEvent.Types.ASSISTANT_MESSAGE, "assistant", "done"),
                event(3, SessionEvent.Types.EXECUTION_END, JSON.createObjectNode()
                        .put("executionId", "exec-1")
                        .put("selectedMode", "react")
                        .put("outcome", "succeeded")
                        .put("status", "completed"))));

        SessionProjection.ExecutionEnvelope envelope =
                projection.executionEnvelopes().get("exec-1");
        assertNotNull(envelope);
        assertEquals(0L, envelope.startSequence());
        assertEquals(1L, envelope.userSequence());
        assertEquals(2L, envelope.assistantSequence());
        assertEquals(3L, envelope.endSequence());
        assertEquals("completed", envelope.status());
    }

    @Test
    void rejectsDuplicateStartAndEndWithoutStart() {
        SessionEvent start = event(0, SessionEvent.Types.EXECUTION_START, startPayload());
        assertThrows(SessionReplayer.CorruptSessionException.class, () ->
                new SessionReplayer().replay(manifest(), List.of(
                        start, event(1, SessionEvent.Types.EXECUTION_START, startPayload()))));
        assertThrows(SessionReplayer.CorruptSessionException.class, () ->
                new SessionReplayer().replay(manifest(), List.of(event(
                        0, SessionEvent.Types.EXECUTION_END,
                        JSON.createObjectNode().put("executionId", "missing")
                                .put("selectedMode", "react")
                                .put("outcome", "failed")
                                .put("status", "failed")))));
    }

    private static ObjectNode startPayload() {
        return JSON.createObjectNode()
                .put("executionId", "exec-1")
                .put("ordinal", 4)
                .put("explicitMode", "react");
    }

    private static SessionEvent message(long sequence, String type, String role, String content) {
        ObjectNode payload = JSON.createObjectNode().put("executionId", "exec-1");
        payload.set("message", JSON.valueToTree("user".equals(role)
                ? LlmClient.Message.user(content)
                : LlmClient.Message.assistant(content)));
        payload.set("conversation", JSON.createObjectNode()
                .put("role", role)
                .put("content", content)
                .put("mode", "react")
                .put("executionId", "exec-1"));
        return event(sequence, type, payload);
    }

    private static SessionEvent event(long sequence, String type, ObjectNode payload) {
        return new SessionEvent(SessionEvent.CURRENT_SCHEMA_VERSION, SESSION_ID,
                sequence, sequence + 1, type, "react", "agent", "test", false,
                SessionEvent.SurfaceOperation.none(), payload);
    }

    private static SessionManifest manifest() {
        return new SessionManifest(SessionEvent.CURRENT_SCHEMA_VERSION, SESSION_ID, "C:\\workspace",
                null, null, 1, 1, null, "react", "agent", false, -1, false, null);
    }
}
