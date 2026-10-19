package com.codeagent.model.recovery;

/**
 * Durable exact identity; an instance alone does not attest a source log or current lease.
 */
public record TaskAttemptBinding(
        String workspace,
        String parentSessionId,
        String executionId,
        String planId,
        String taskId,
        int attemptNo,
        String attemptId,
        String childSessionId,
        String runtimeOwnerId,
        int runtimeAttempt,
        String sourceAttemptId,
        long createdAt) {
}
