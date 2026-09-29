package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.RoutingSource;

public record TopLevelExecutionResult(
        ExecutionMode mode,
        RoutingSource routingSource,
        ExecutionOutcome outcome,
        String result,
        String error) {

    public TopLevelExecutionResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
    }

    public static TopLevelExecutionResult succeeded(String result) {
        return new TopLevelExecutionResult(null, null, ExecutionOutcome.SUCCEEDED, result, null);
    }

    public static TopLevelExecutionResult failed(String error) {
        return new TopLevelExecutionResult(null, null, ExecutionOutcome.FAILED, null, error);
    }
}
