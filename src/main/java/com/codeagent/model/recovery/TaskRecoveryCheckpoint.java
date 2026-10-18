package com.codeagent.model.recovery;

import java.util.List;

public record TaskRecoveryCheckpoint(
        String checkpointId,
        String attemptId,
        int checkpointNo,
        long childLastSequence,
        List<RecoveryFact> facts,
        List<RecoveryUnknown> unknowns) {

    public TaskRecoveryCheckpoint {
        facts = RecoveryBounds.copy(facts, 200);
        unknowns = RecoveryBounds.copy(unknowns, 200);
    }

    @Override
    public String toString() {
        return "TaskRecoveryCheckpoint[checkpointNo=" + checkpointNo + ", factCount=" + facts.size() + ", unknownCount=" + unknowns.size() + "]";
    }
}
