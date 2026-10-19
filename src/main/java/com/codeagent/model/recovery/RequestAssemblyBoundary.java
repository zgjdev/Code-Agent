package com.codeagent.model.recovery;

/** Explicit dual identity: E1 owns results, E2 owns the current durable user input. */
public record RequestAssemblyBoundary(String resultExecutionId, String inputExecutionId, long inputUserSequence) {
    public RequestAssemblyBoundary {
        if (resultExecutionId == null || resultExecutionId.isBlank() || inputExecutionId == null
                || inputExecutionId.isBlank() || inputUserSequence < 0)
            throw new IllegalArgumentException("Invalid recovery request identity");
    }
}
