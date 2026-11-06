package com.codeagent.context;

import java.util.List;
import java.util.Objects;

public record ContextEditBatch(String operationId, String scopeId, long expectedSourceRevision,
                               long expectedContextGeneration, String expectedProtectionDigest,
                               String baseDigest, List<ContextEdit> edits) {
    public ContextEditBatch {
        if (operationId == null || operationId.isBlank() || scopeId == null || scopeId.isBlank()
                || expectedSourceRevision < 0 || expectedContextGeneration < 0) {
            throw new IllegalArgumentException("Invalid context transaction identity");
        }
        Objects.requireNonNull(expectedProtectionDigest);
        Objects.requireNonNull(baseDigest);
        edits = List.copyOf(edits);
        if (edits.isEmpty()) throw new IllegalArgumentException("Empty context transaction");
    }
}
