package com.codeagent.history;

/** Display-only, sanitized historical fact. offset is a Unicode code point offset in this event. */
public record TranscriptEntry(long sequence, Role role, Mode mode, String text,
                              BoundaryKind boundaryKind, int textOffset, boolean continued) {
    public enum Role { USER, ASSISTANT, CONTEXT }
    public enum Mode { REACT, PLAN, CONTEXT }
    public enum BoundaryKind { MESSAGE, CLEAR, COMPACTION, INTERRUPTED_FRAGMENT, INTERRUPTED }
    @Override public String toString() {
        return "TranscriptEntry[sequence=" + sequence + ", role=" + role + ", boundary=" + boundaryKind + "]";
    }
}
