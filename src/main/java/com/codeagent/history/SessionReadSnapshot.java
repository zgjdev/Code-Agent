package com.codeagent.history;

import java.util.List;

/** Validated immutable read prefix. Raw events are for trusted domain readers, never display. */
public record SessionReadSnapshot(SessionManifest manifest, List<SessionEvent> events, long lastSequence) {
    public SessionReadSnapshot {
        events = copy(events);
    }

    @Override public List<SessionEvent> events() { return copy(events); }

    private static List<SessionEvent> copy(List<SessionEvent> events) {
        return events.stream().map(e -> new SessionEvent(e.schemaVersion(), e.sessionId(), e.sequence(),
                e.timestamp(), e.type(), e.mode(), e.actor(), e.source(), e.ignorable(), e.surface(),
                e.payload() == null ? null : e.payload().deepCopy())).toList();
    }

    @Override public String toString() {
        return "SessionReadSnapshot[lastSequence=" + lastSequence + ", events=" + events.size() + "]";
    }
}
