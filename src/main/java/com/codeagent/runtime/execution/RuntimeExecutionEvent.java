package com.codeagent.runtime.execution;

import java.util.Objects;

public record RuntimeExecutionEvent(WorkspaceKey workspaceKey, String sessionId, String executionId,
                                    String kind, Object payload) {
    public RuntimeExecutionEvent {
        Objects.requireNonNull(workspaceKey, "workspaceKey");
        if (sessionId == null || sessionId.isBlank() || executionId == null || executionId.isBlank()
                || kind == null || kind.isBlank()) throw new IllegalArgumentException("Incomplete execution event identity");
    }
}
