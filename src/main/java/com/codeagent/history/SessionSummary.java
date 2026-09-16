package com.codeagent.history;

/** Lightweight metadata used by session listing and automatic resume selection. */
public record SessionSummary(
        String sessionId,
        String workspace,
        String provider,
        String model,
        long createdAt,
        long updatedAt,
        boolean closed,
        long lastEventSequence,
        boolean resumeUnsafe) {

    static SessionSummary from(SessionManifest manifest) {
        return new SessionSummary(
                manifest.sessionId(), manifest.workspace(), manifest.provider(), manifest.model(),
                manifest.createdAt(), manifest.updatedAt(), manifest.closed(),
                manifest.lastEventSequence(), manifest.resumeUnsafe());
    }
}
