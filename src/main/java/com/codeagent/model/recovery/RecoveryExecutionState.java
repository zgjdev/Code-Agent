package com.codeagent.model.recovery;

public record RecoveryExecutionState(
        String executionId,
        String originalMode,
        String currentMode,
        String recordedStatus,
        String dispatchState,
        int recoveryGeneration,
        boolean unfinishedPlan,
        boolean continuationCandidate,
        String goalExcerpt) {
    public RecoveryExecutionState {
        goalExcerpt = RecoveryBounds.text(goalExcerpt, 512);
    }

    public RecoveryExecutionState(String executionId, String originalMode, String currentMode,
            String recordedStatus, String dispatchState, int recoveryGeneration,
            boolean unfinishedPlan, boolean continuationCandidate) {
        this(executionId, originalMode, currentMode, recordedStatus, dispatchState,
                recoveryGeneration, unfinishedPlan, continuationCandidate, null);
    }

    @Override public String toString() {
        return "RecoveryExecutionState[executionId=" + executionId + ", generation=" + recoveryGeneration + "]";
    }
}
