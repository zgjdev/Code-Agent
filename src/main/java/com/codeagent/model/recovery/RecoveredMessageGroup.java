package com.codeagent.model.recovery;

import java.util.List;

public record RecoveredMessageGroup(
        String canonicalOriginChildId,
        String sourceAttemptId,
        long firstSequence,
        long lastSequence,
        String contentHash,
        RecoveryCompleteness completeness,
        List<RecoveryMessage> messages,
        boolean redacted) {

    public RecoveredMessageGroup {
        messages = RecoveryBounds.copy(messages, 65);
    }

    @Override
    public String toString() {
        return "RecoveredMessageGroup[firstSequence=" + firstSequence + ", lastSequence=" + lastSequence + ", completeness=" + completeness + "]";
    }
}
