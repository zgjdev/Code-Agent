package com.codeagent.runtime.execution;

import com.codeagent.runtime.CancellationToken;

@FunctionalInterface
public interface TopLevelExecutionRunner {
    TopLevelExecutionResult run(RuntimeExecution execution, CancellationToken cancellationToken) throws Exception;
}
