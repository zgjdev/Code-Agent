package com.codeagent.history;

import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates an event stream and derives the model-visible session surface. */
public final class SessionReplayer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KNOWN_TYPES = Set.of(
            SessionEvent.Types.SESSION_START,
            SessionEvent.Types.SESSION_END,
            SessionEvent.Types.SESSION_INTERRUPT,
            SessionEvent.Types.TURN_START,
            SessionEvent.Types.TURN_END,
            SessionEvent.Types.REQUEST_STARTED,
            SessionEvent.Types.REQUEST_SNAPSHOT,
            SessionEvent.Types.REQUEST_FINISHED,
            SessionEvent.Types.REQUEST_FAILED,
            SessionEvent.Types.SYSTEM_MESSAGE,
            SessionEvent.Types.USER_MESSAGE,
            SessionEvent.Types.ASSISTANT_MESSAGE,
            SessionEvent.Types.TOOL_CALL,
            SessionEvent.Types.TOOL_EXECUTION_STARTED,
            SessionEvent.Types.TOOL_RESULT,
            SessionEvent.Types.PROVIDER_USAGE,
            SessionEvent.Types.SURFACE_CLEAR,
            SessionEvent.Types.IMAGE_PRUNED,
            SessionEvent.Types.COMPACTION_START,
            SessionEvent.Types.COMPACTION_SUMMARY,
            SessionEvent.Types.COMPACTION_END,
            SessionEvent.Types.RETRY_START,
            SessionEvent.Types.RETRY_END,
            SessionEvent.Types.LEGACY_EVENT);

    public SessionProjection replay(SessionManifest manifest, List<SessionEvent> events) {
        if (manifest == null) {
            throw new CorruptSessionException("missing session manifest");
        }
        MutableProjection state = new MutableProjection(manifest.closed());
        long expectedSequence = 0;
        for (SessionEvent event : events == null ? List.<SessionEvent>of() : events) {
            validate(manifest, event, expectedSequence);
            expectedSequence++;
            state.lastAppliedSequence = event.sequence();
            state.cleanlyClosed = false;
            if (!KNOWN_TYPES.contains(event.type())) {
                if (event.ignorable()) {
                    continue;
                }
                throw new UnsupportedSessionEventException("unsupported required event: " + event.type());
            }
            apply(state, event);
        }
        for (String compactionId : state.compactions.keySet()) {
            state.warnings.add("incomplete compaction ignored: " + compactionId);
        }
        return state.freeze();
    }

    private static void validate(SessionManifest manifest, SessionEvent event, long expectedSequence) {
        if (event == null) {
            throw new CorruptSessionException("null event at sequence " + expectedSequence);
        }
        if (!manifest.sessionId().equals(event.sessionId())) {
            throw new CorruptSessionException("event belongs to another session: " + event.sessionId());
        }
        if (event.sequence() != expectedSequence) {
            throw new CorruptSessionException(
                    "expected sequence " + expectedSequence + " but found " + event.sequence());
        }
        if (event.schemaVersion() > SessionEvent.CURRENT_SCHEMA_VERSION && !event.ignorable()) {
            throw new UnsupportedSessionEventException(
                    "unsupported schema version: " + event.schemaVersion());
        }
    }

    private static void apply(MutableProjection state, SessionEvent event) {
        JsonNode payload = event.payload();
        String compactionId = text(payload, "compactionId");
        if (SessionEvent.Types.COMPACTION_START.equals(event.type())) {
            require(compactionId, "compaction/start requires compactionId");
            if (state.compactions.putIfAbsent(compactionId, new ArrayList<>()) != null) {
                throw new CorruptSessionException("duplicate compaction start: " + compactionId);
            }
            return;
        }
        if (SessionEvent.Types.COMPACTION_END.equals(event.type())) {
            completeCompaction(state, event, compactionId);
            return;
        }
        if (isMessageEvent(event.type()) && compactionId != null && state.compactions.containsKey(compactionId)) {
            state.compactions.get(compactionId).add(event);
            return;
        }

        switch (event.type()) {
            case SessionEvent.Types.REQUEST_STARTED -> state.incompleteRequests.add(requiredText(payload, "requestId"));
            case SessionEvent.Types.REQUEST_FINISHED, SessionEvent.Types.REQUEST_FAILED ->
                    state.incompleteRequests.remove(requiredText(payload, "requestId"));
            case SessionEvent.Types.TOOL_CALL -> {
                String invocationId = requiredText(payload, "invocationId");
                state.pendingTools.put(invocationId, new SessionProjection.PendingToolInvocation(
                        invocationId, text(payload, "name"), text(payload, "arguments")));
            }
            case SessionEvent.Types.TOOL_RESULT -> {
                state.pendingTools.remove(requiredText(payload, "invocationId"));
                applySurface(state, event);
            }
            case SessionEvent.Types.SESSION_END -> state.cleanlyClosed = true;
            case SessionEvent.Types.SYSTEM_MESSAGE,
                 SessionEvent.Types.USER_MESSAGE,
                 SessionEvent.Types.ASSISTANT_MESSAGE -> applySurface(state, event);
            case SessionEvent.Types.SURFACE_CLEAR -> applySurface(state, event);
            default -> {
                // Lifecycle and diagnostic events do not directly change the active surface.
            }
        }
    }

    private static void completeCompaction(MutableProjection state, SessionEvent event, String compactionId) {
        require(compactionId, "compaction/end requires compactionId");
        List<SessionEvent> staged = state.compactions.remove(compactionId);
        if (staged == null) {
            throw new CorruptSessionException("compaction end without start: " + compactionId);
        }
        if (!"completed".equals(text(event.payload(), "status"))) {
            return;
        }
        for (SessionEvent stagedEvent : staged) {
            applySurface(state, stagedEvent);
        }
        state.compactionGeneration++;
    }

    private static void applySurface(MutableProjection state, SessionEvent event) {
        String operation = event.surface().op();
        switch (operation) {
            case "none" -> {
                return;
            }
            case "clear" -> state.surface.clear();
            case "append" -> state.surface.add(messageNode(event));
            case "replace" -> replace(state.surface, event);
            default -> throw new CorruptSessionException("unknown surface operation: " + operation);
        }
        state.historyVersion++;
    }

    private static SessionProjection.SurfaceNode messageNode(SessionEvent event) {
        JsonNode message = event.payload() == null ? null : event.payload().get("message");
        if (message == null || message.isNull()) {
            throw new CorruptSessionException("surface event has no message: " + event.type());
        }
        try {
            return new SessionProjection.SurfaceNode(
                    event.sequence(), JSON.treeToValue(message, LlmClient.Message.class));
        } catch (Exception e) {
            throw new CorruptSessionException("invalid message at sequence " + event.sequence(), e);
        }
    }

    private static void replace(List<SessionProjection.SurfaceNode> surface, SessionEvent event) {
        Long startSequence = event.surface().startSequence();
        Long endSequence = event.surface().endSequence();
        if (startSequence == null || endSequence == null || startSequence > endSequence) {
            throw new CorruptSessionException("invalid replacement range");
        }
        int start = indexOf(surface, startSequence);
        int end = indexOf(surface, endSequence);
        if (start < 0 || end < start) {
            throw new CorruptSessionException(
                    "replacement range is not present on active surface: " + startSequence + ".." + endSequence);
        }
        surface.subList(start, end + 1).clear();
        surface.add(start, messageNode(event));
    }

    private static int indexOf(List<SessionProjection.SurfaceNode> surface, long sequence) {
        for (int i = 0; i < surface.size(); i++) {
            if (surface.get(i).sequence() == sequence) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isMessageEvent(String type) {
        return SessionEvent.Types.SYSTEM_MESSAGE.equals(type)
                || SessionEvent.Types.USER_MESSAGE.equals(type)
                || SessionEvent.Types.ASSISTANT_MESSAGE.equals(type)
                || SessionEvent.Types.TOOL_RESULT.equals(type);
    }

    private static String requiredText(JsonNode payload, String field) {
        String value = text(payload, field);
        require(value, "missing payload field: " + field);
        return value;
    }

    private static String text(JsonNode payload, String field) {
        if (payload == null || payload.path(field).isMissingNode() || payload.path(field).isNull()) {
            return null;
        }
        String value = payload.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new CorruptSessionException(message);
        }
    }

    private static final class MutableProjection {
        private final List<SessionProjection.SurfaceNode> surface = new ArrayList<>();
        private final Set<String> incompleteRequests = new LinkedHashSet<>();
        private final Map<String, SessionProjection.PendingToolInvocation> pendingTools = new LinkedHashMap<>();
        private final Map<String, List<SessionEvent>> compactions = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();
        private long lastAppliedSequence = -1;
        private long historyVersion;
        private long compactionGeneration;
        private boolean cleanlyClosed;

        private MutableProjection(boolean cleanlyClosed) {
            this.cleanlyClosed = cleanlyClosed;
        }

        private SessionProjection freeze() {
            return new SessionProjection(surface, lastAppliedSequence, historyVersion,
                    compactionGeneration, null, incompleteRequests, pendingTools,
                    cleanlyClosed, warnings);
        }
    }

    public static class CorruptSessionException extends IllegalArgumentException {
        public CorruptSessionException(String message) {
            super(message);
        }

        public CorruptSessionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class UnsupportedSessionEventException extends CorruptSessionException {
        public UnsupportedSessionEventException(String message) {
            super(message);
        }
    }
}
