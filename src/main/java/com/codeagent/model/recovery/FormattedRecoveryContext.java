package com.codeagent.model.recovery;

import java.util.Objects;

/** A safe derived model observation, never a durable conversation message or authority. */
public record FormattedRecoveryContext(String content, String stateFingerprint, int estimatedTokens,
        int omittedExecutionCount, int omittedPlanCount, int omittedTaskCount, int omittedFactCount,
        int omittedUnknownCount, int omittedWarningCount, RecoveryCompleteness completeness) {
    public FormattedRecoveryContext {
        Objects.requireNonNull(content);
        Objects.requireNonNull(stateFingerprint);
        Objects.requireNonNull(completeness);
    }
    @Override public String toString() {
        return "FormattedRecoveryContext[tokens=" + estimatedTokens + ", completeness=" + completeness + "]";
    }
}
