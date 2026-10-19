package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.RoutingSource;

import java.time.Instant;

public record RuntimeExecution(
        String id,
        String workspace,
        WorkspaceKey workspaceKey,
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
        String ownerId,
        boolean legacyUnbound,
        Instant cancelRequestedAt,
        String interruptionReason,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt,
        String requestKind,
        String dispatchState,
        int recoveryGeneration,
        String continuationRequestId,
        String controlTargetExecutionId,
        Integer controlTargetGeneration,
        String controlOwnerId,
        String currentPolicyInput,
        RequestSemantics requestSemantics) {

    public RuntimeExecution(String id, String workspace, WorkspaceKey workspaceKey, String sessionId,
            Long ordinal, ExecutionStatus status, String submittedInput, String resolvedTaskInput,
            ExecutionMode explicitMode, ExecutionMode selectedMode, RoutingSource routingSource,
            ExecutionOutcome outcome, String result, String error, int attempt, String ownerId,
            boolean legacyUnbound, Instant cancelRequestedAt, String interruptionReason, Instant createdAt,
            Instant startedAt, Instant finishedAt, Instant updatedAt, String requestKind, String dispatchState,
            int recoveryGeneration, String continuationRequestId, String controlTargetExecutionId,
            Integer controlTargetGeneration, String controlOwnerId, String currentPolicyInput) {
        this(id, workspace, workspaceKey, sessionId, ordinal, status, submittedInput, resolvedTaskInput,
                explicitMode, selectedMode, routingSource, outcome, result, error, attempt, ownerId,
                legacyUnbound, cancelRequestedAt, interruptionReason, createdAt, startedAt, finishedAt, updatedAt,
                requestKind, dispatchState, recoveryGeneration, continuationRequestId, controlTargetExecutionId,
                controlTargetGeneration, controlOwnerId, currentPolicyInput, null);
    }

    public RuntimeExecution(String id, String workspace, WorkspaceKey workspaceKey, String sessionId,
            Long ordinal, ExecutionStatus status, String submittedInput, String resolvedTaskInput,
            ExecutionMode explicitMode, ExecutionMode selectedMode, RoutingSource routingSource,
            ExecutionOutcome outcome, String result, String error, int attempt, String ownerId,
            boolean legacyUnbound, Instant cancelRequestedAt, String interruptionReason, Instant createdAt,
            Instant startedAt, Instant finishedAt, Instant updatedAt) {
        this(id, workspace, workspaceKey, sessionId, ordinal, status, submittedInput, resolvedTaskInput,
                explicitMode, selectedMode, routingSource, outcome, result, error, attempt, ownerId,
                legacyUnbound, cancelRequestedAt, interruptionReason, createdAt, startedAt, finishedAt, updatedAt,
                "task", "ready", 0, null, null, null, null, null);
    }

    public boolean awaitingInput() { return !terminal() && "awaiting_input".equals(dispatchState); }

    @Override public String toString() {
        return "RuntimeExecution[id=" + id + ", status=" + status + ", attempt=" + attempt
                + ", dispatchState=" + dispatchState + ", recoveryGeneration=" + recoveryGeneration + "]";
    }

    public boolean terminal() {
        return status.terminal();
    }

    public ExecutionClaim claim() {
        if (ownerId == null || attempt < 1) {
            throw new IllegalStateException("Execution has no claim identity: " + id);
        }
        return new ExecutionClaim(id, ownerId, attempt);
    }
}
