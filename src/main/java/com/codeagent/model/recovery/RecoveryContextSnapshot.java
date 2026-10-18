package com.codeagent.model.recovery;

import java.util.List;

public record RecoveryContextSnapshot(
        String workspaceKey,
        String parentSessionId,
        long capturedAt,
        long parentLastSequence,
        String stateFingerprint,
        List<RecoveryExecutionState> executions,
        List<RecoveryPlanState> plans,
        List<RecoveryWarning> warnings) {

    public RecoveryContextSnapshot {
        executions = RecoveryBounds.copy(executions, 1000);
        plans = RecoveryBounds.copy(plans, 1000);
        warnings = RecoveryBounds.copy(warnings, 1000);
    }

    @Override
    public String toString() {
        return "RecoveryContextSnapshot[sequence=" + parentLastSequence + ", executionCount=" + executions.size() + ", planCount=" + plans.size() + "]";
    }
}
