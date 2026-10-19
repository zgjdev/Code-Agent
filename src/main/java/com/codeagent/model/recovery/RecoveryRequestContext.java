package com.codeagent.model.recovery;

import java.util.Objects;

/**
 * Trusted worker supplies a freshly validated snapshot; the context grants no release/permission.
 * originalSubmittedInput is the durable E1 request's original text, never expanded delivery or a
 * sanitized goal excerpt. It is required only when E1 has not persisted its own user event yet.
 * The separate Agent submittedUserInput argument must match E2's durable user event exactly.
 */
public record RecoveryRequestContext(RecoveryContextSnapshot snapshot, RequestAssemblyBoundary boundary, boolean statusOnly,
                                     String originalSubmittedInput) {
    public RecoveryRequestContext {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(boundary);
        if (statusOnly && !boundary.resultExecutionId().equals(boundary.inputExecutionId()))
            throw new IllegalArgumentException("A status reply must own its current input");
    }
    public RecoveryRequestContext(RecoveryContextSnapshot snapshot, RequestAssemblyBoundary boundary, boolean statusOnly) {
        this(snapshot, boundary, statusOnly, null);
    }
    @Override public String toString() { return "RecoveryRequestContext[statusOnly=" + statusOnly + "]"; }
}
