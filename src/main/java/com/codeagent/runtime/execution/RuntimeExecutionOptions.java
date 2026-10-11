package com.codeagent.runtime.execution;

public record RuntimeExecutionOptions(int maxConcurrentExecutions) {
    public RuntimeExecutionOptions {
        if (maxConcurrentExecutions < 1 || maxConcurrentExecutions > 4) {
            throw new IllegalArgumentException("Top-level concurrency must be between 1 and 4");
        }
    }
    public static RuntimeExecutionOptions defaults() { return new RuntimeExecutionOptions(2); }
}
