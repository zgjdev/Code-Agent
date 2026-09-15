package com.codeagent.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** One immutable entry in a durable session log. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionEvent(
        int schemaVersion,
        String sessionId,
        long sequence,
        long timestamp,
        String type,
        String mode,
        String actor,
        String source,
        boolean ignorable,
        SurfaceOperation surface,
        JsonNode payload) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public SessionEvent {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        surface = surface == null ? SurfaceOperation.none() : surface;
    }

    public static final class Types {
        public static final String SESSION_START = "session/start";
        public static final String SESSION_END = "session/end";
        public static final String SESSION_INTERRUPT = "session/interrupt";
        public static final String TURN_START = "turn/start";
        public static final String TURN_END = "turn/end";
        public static final String REQUEST_STARTED = "request/started";
        public static final String REQUEST_SNAPSHOT = "request/snapshot";
        public static final String REQUEST_FINISHED = "request/finished";
        public static final String REQUEST_FAILED = "request/failed";
        public static final String SYSTEM_MESSAGE = "system/message";
        public static final String USER_MESSAGE = "user/message";
        public static final String ASSISTANT_MESSAGE = "assistant/message";
        public static final String TOOL_CALL = "tool/call";
        public static final String TOOL_EXECUTION_STARTED = "tool/execution-started";
        public static final String TOOL_RESULT = "tool/result";
        public static final String PROVIDER_USAGE = "provider/usage";
        public static final String SURFACE_CLEAR = "surface/clear";
        public static final String IMAGE_PRUNED = "image/pruned";
        public static final String COMPACTION_START = "compaction/start";
        public static final String COMPACTION_SUMMARY = "compaction/summary";
        public static final String COMPACTION_END = "compaction/end";
        public static final String RETRY_START = "retry/start";
        public static final String RETRY_END = "retry/end";
        public static final String LEGACY_EVENT = "legacy/event";

        private Types() {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SurfaceOperation(String op, Long startSequence, Long endSequence) {
        public SurfaceOperation {
            op = op == null || op.isBlank() ? "none" : op;
        }

        public static SurfaceOperation append() {
            return new SurfaceOperation("append", null, null);
        }

        public static SurfaceOperation replace(long startSequence, long endSequence) {
            return new SurfaceOperation("replace", startSequence, endSequence);
        }

        public static SurfaceOperation clear() {
            return new SurfaceOperation("clear", null, null);
        }

        public static SurfaceOperation none() {
            return new SurfaceOperation("none", null, null);
        }
    }
}
