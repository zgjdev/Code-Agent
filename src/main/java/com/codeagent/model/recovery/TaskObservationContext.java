package com.codeagent.model.recovery;

import java.util.Objects;

/**
 * Explicit immutable identity propagated into each tool worker.
 */
public record TaskObservationContext(TaskAttemptBinding binding, String batchId, int callOrdinal) {

    public TaskObservationContext {
        Objects.requireNonNull(binding);
        Objects.requireNonNull(batchId);
        if (callOrdinal < 0)
            throw new IllegalArgumentException("Negative call ordinal");
    }
}
