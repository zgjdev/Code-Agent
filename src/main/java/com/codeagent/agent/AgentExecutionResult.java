package com.codeagent.agent;

import com.codeagent.runtime.execution.ExecutionOutcome;

public record AgentExecutionResult(ExecutionOutcome outcome, String result, String error) {
    public AgentExecutionResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
    }

    public static AgentExecutionResult succeeded(String result) {
        return new AgentExecutionResult(ExecutionOutcome.SUCCEEDED, nullToEmpty(result), null);
    }

    public static AgentExecutionResult partial(String result) {
        return new AgentExecutionResult(ExecutionOutcome.PARTIAL, nullToEmpty(result), null);
    }

    public static AgentExecutionResult canceled(String result) {
        return new AgentExecutionResult(ExecutionOutcome.CANCELED, nullToEmpty(result), null);
    }

    public static AgentExecutionResult failed(String displayResult, String error) {
        return new AgentExecutionResult(ExecutionOutcome.FAILED, nullToEmpty(displayResult), error);
    }

    public String displayResult() {
        return result == null ? "" : result;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
