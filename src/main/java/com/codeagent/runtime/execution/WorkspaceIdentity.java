package com.codeagent.runtime.execution;

import java.nio.file.Path;
import java.util.Objects;

public record WorkspaceIdentity(WorkspaceKey key, Path canonicalRoot) {
    public WorkspaceIdentity {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        if (!canonicalRoot.isAbsolute() || !canonicalRoot.normalize().equals(canonicalRoot)) {
            throw new IllegalArgumentException("canonicalRoot must be absolute and normalized");
        }
    }
}
