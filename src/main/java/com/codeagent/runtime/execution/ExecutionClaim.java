package com.codeagent.runtime.execution;

import java.util.Objects;

public record ExecutionClaim(String executionId, String ownerId, int attempt) {
    public ExecutionClaim {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(ownerId, "ownerId");
        if (executionId.isBlank() || ownerId.isBlank() || attempt < 1) {
            throw new IllegalArgumentException("execution claim identity is invalid");
        }
    }
}
