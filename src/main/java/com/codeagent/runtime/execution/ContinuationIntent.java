package com.codeagent.runtime.execution;

/** Classification is a proposal, never an admission or permission token. */
public record ContinuationIntent(Kind kind, RequestSemantics semantics, String targetExecutionId, Integer targetGeneration) {
    public enum Kind { NEW_TURN, CONTINUE, CLARIFY }
    public ContinuationIntent {
        java.util.Objects.requireNonNull(kind);
        java.util.Objects.requireNonNull(semantics);
        if (kind == Kind.NEW_TURN && semantics != RequestSemantics.NEW_REQUEST && semantics != RequestSemantics.STATUS_QUESTION
                || kind == Kind.CONTINUE && (semantics != RequestSemantics.CONTINUE || targetExecutionId == null || targetExecutionId.isBlank() || targetGeneration == null || targetGeneration < 0)
                || kind == Kind.CLARIFY && semantics != RequestSemantics.CLARIFY
                || kind != Kind.CONTINUE && (targetExecutionId != null || targetGeneration != null))
            throw new IllegalArgumentException("Invalid continuation intent tuple");
    }
    public static ContinuationIntent clarify() { return new ContinuationIntent(Kind.CLARIFY, RequestSemantics.CLARIFY, null, null); }
}
