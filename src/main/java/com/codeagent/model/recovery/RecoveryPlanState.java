package com.codeagent.model.recovery;

import java.util.List;

public record RecoveryPlanState(
        String planId,
        String executionId,
        String goal,
        String recordedStatus,
        String updatedAt,
        List<RecoveryTaskState> tasks) {

    public RecoveryPlanState {
        goal = RecoveryBounds.text(goal, 2048);
        tasks = RecoveryBounds.copy(tasks, 1000);
    }

    public boolean unfinished() {
        return "CREATED".equals(recordedStatus) || "RUNNING".equals(recordedStatus);
    }

    @Override
    public String toString() {
        return "RecoveryPlanState[planId=" + planId + ", status=" + recordedStatus + ", taskCount=" + tasks.size() + "]";
    }
}
