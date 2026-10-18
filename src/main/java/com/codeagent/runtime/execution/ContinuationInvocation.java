package com.codeagent.runtime.execution;

import com.codeagent.model.recovery.RecoveryRequestContext;

/** Prepared on the actual E1 worker; currentInput is only E2's durable submittedInput. */
public record ContinuationInvocation(RuntimeExecution execution, RuntimeExecution inputExecution,
        String currentInput, RecoveryRequestContext recoveryContext) {
    public ContinuationInvocation {
        if (execution == null || inputExecution == null || recoveryContext == null
                || !inputExecution.submittedInput().equals(currentInput)
                || !execution.id().equals(recoveryContext.boundary().resultExecutionId())
                || !inputExecution.id().equals(recoveryContext.boundary().inputExecutionId()))
            throw new IllegalArgumentException("Continuation invocation identity mismatch");
    }
}
