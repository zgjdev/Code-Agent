package com.codeagent.runtime.execution;

import java.util.Objects;

public record WorkspaceKey(String value) {
    public WorkspaceKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("workspace key must not be blank");
        }
    }
}
