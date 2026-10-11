package com.codeagent.runtime.execution;

@FunctionalInterface
public interface WorkspaceRuntimeFactory {
    WorkspaceRuntime create(WorkspaceIdentity identity, ExecutionEventSink sink) throws Exception;
}
