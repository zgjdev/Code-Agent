package com.codeagent.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Small, repairable index for a session. The event log remains authoritative. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionManifest(
        int schemaVersion,
        String sessionId,
        String workspace,
        String provider,
        String model,
        long createdAt,
        long updatedAt,
        String parentSessionId,
        String mode,
        String actor,
        boolean closed,
        long lastEventSequence,
        boolean resumeUnsafe,
        String legacySourceSha256) {
}
