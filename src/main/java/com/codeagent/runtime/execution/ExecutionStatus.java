package com.codeagent.runtime.execution;

import java.util.Locale;

public enum ExecutionStatus {
    ENQUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELED;

    public String databaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }

    public static ExecutionStatus fromDatabase(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Execution status must not be null");
        }
        for (ExecutionStatus status : values()) {
            if (status.databaseValue().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown execution status: " + value);
    }
}
