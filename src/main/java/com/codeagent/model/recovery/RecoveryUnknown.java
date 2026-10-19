package com.codeagent.model.recovery;

public record RecoveryUnknown(
        Kind kind,
        String taskId,
        String callId,
        String reason,
        String requiresVerification) {

    public enum Kind {

        INTERNAL_PROGRESS_UNKNOWN, INVOCATION_OUTCOME_UNKNOWN, SOURCE_INCOMPLETE
    }

    public RecoveryUnknown {
        reason = RecoveryBounds.exact(reason, 256);
        requiresVerification = RecoveryBounds.exact(requiresVerification, 256);
    }

    @Override
    public String toString() {
        return "RecoveryUnknown[kind=" + kind + "]";
    }
}
