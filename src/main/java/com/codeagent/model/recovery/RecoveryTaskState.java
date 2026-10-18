package com.codeagent.model.recovery;

import java.util.List;

public record RecoveryTaskState(
        String planId,
        String taskId,
        int ordinal,
        String description,
        String recordedStatus,
        String recoveryStatus,
        List<String> dependencies,
        String recordedResult,
        List<RecoveryFact> facts,
        List<RecoveryUnknown> unknowns,
        List<RecoverySourceRef> sources,
        RecoveryCompleteness completeness) {

    public RecoveryTaskState {
        description = RecoveryBounds.text(description, 2048);
        recordedResult = RecoveryBounds.text(recordedResult, 2048);
        dependencies = RecoveryBounds.copy(dependencies, 1000);
        facts = RecoveryBounds.copy(facts, 200);
        unknowns = RecoveryBounds.copy(unknowns, 200);
        sources = RecoveryBounds.copy(sources, 200);
    }

    @Override
    public String toString() {
        return "RecoveryTaskState[taskId=" + taskId + ", recordedStatus=" + recordedStatus + ", recoveryStatus=" + recoveryStatus + ", completeness=" + completeness + "]";
    }
}
