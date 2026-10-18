package com.codeagent.runtime.execution;

/** Immutable request contract. A null database value means unknown, never NEW_REQUEST. */
public enum RequestSemantics {
    NEW_REQUEST, STATUS_QUESTION, CONTINUE, CLARIFY;

    static RequestSemantics decode(String value) {
        return value == null ? null : valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
