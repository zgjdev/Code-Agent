package com.codeagent.runtime.execution;

@FunctionalInterface
public interface ExecutionEventSink {
    void accept(RuntimeExecutionEvent event);
    default boolean supportsInteractions() { return false; }
    static ExecutionEventSink discarding() { return event -> { }; }
}
