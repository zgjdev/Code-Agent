package com.codeagent.history;

/** offset=0 means after sequence; positive offset continues that sequence's sanitized text. */
public record TranscriptCursor(long afterSequence, int textOffset) {
    public TranscriptCursor {
        if (afterSequence < -1 || textOffset < 0 || (afterSequence < 0 && textOffset != 0)) {
            throw new IllegalArgumentException("invalid transcript cursor");
        }
    }
    public static TranscriptCursor start() { return new TranscriptCursor(-1, 0); }
}
