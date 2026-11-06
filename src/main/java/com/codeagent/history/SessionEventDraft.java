package com.codeagent.history;

import com.fasterxml.jackson.databind.JsonNode;

/** Event data supplied by a caller before the store assigns identity and ordering. */
public record SessionEventDraft(
        String type,
        String mode,
        String actor,
        String source,
        boolean ignorable,
        SessionEvent.SurfaceOperation surface,
        JsonNode payload,
        int schemaVersion) {
    public SessionEventDraft(String type, String mode, String actor, String source, boolean ignorable,
                             SessionEvent.SurfaceOperation surface, JsonNode payload) {
        this(type, mode, actor, source, ignorable, surface, payload, 2);
    }
}
