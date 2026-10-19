package com.codeagent.history;

import java.util.List;

public record TranscriptPage(List<TranscriptEntry> entries, TranscriptCursor nextCursor,
                             long snapshotLastSequence, boolean hasClearedHistory) {
    public TranscriptPage { entries = List.copyOf(entries); }
    @Override public String toString() {
        return "TranscriptPage[entries=" + entries.size() + ", snapshotLastSequence=" + snapshotLastSequence + "]";
    }
}
