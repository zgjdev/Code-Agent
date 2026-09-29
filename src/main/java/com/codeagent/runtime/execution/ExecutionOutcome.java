package com.codeagent.runtime.execution;

import java.util.Locale;

public enum ExecutionOutcome {
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELED,
    REJECTED;

    public String databaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ExecutionOutcome fromDatabase(String value) {
        if (value == null) {
            return null;
        }
        for (ExecutionOutcome outcome : values()) {
            if (outcome.databaseValue().equals(value)) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("Unknown execution outcome: " + value);
    }
}
