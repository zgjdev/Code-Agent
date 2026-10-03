package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.RoutingSource;

import java.time.Instant;

public record RuntimeExecution(
        String id,
        String workspace,
        String sessionId,
        Long ordinal,
        ExecutionStatus status,
        String submittedInput,
        String resolvedTaskInput,
        ExecutionMode explicitMode,
        ExecutionMode selectedMode,
        RoutingSource routingSource,
        ExecutionOutcome outcome,
        String result,
        String error,
        int attempt,
        boolean legacyUnbound,
        Instant cancelRequestedAt,
        String interruptionReason,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt) {

    public boolean terminal() {
        return status.terminal();
    }
}
