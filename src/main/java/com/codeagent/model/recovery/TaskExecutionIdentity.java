package com.codeagent.model.recovery;

import java.util.Objects;

/**
 * Host-supplied validated current claim; never reconstructed from model output or task workers.
 */
public record TaskExecutionIdentity(
        String executionId,
        String parentSessionId,
        String ownerId,
        int runtimeAttempt) {

    public TaskExecutionIdentity {
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(parentSessionId);
        Objects.requireNonNull(ownerId);
        if (executionId.isBlank() || parentSessionId.isBlank() || ownerId.isBlank() || runtimeAttempt < 1)
            throw new IllegalArgumentException("Invalid task execution identity");
    }
}
