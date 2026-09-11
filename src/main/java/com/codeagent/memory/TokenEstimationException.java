package com.codeagent.memory;

/** Raised when a model-visible content block cannot be priced safely. */
public class TokenEstimationException extends RuntimeException {
    public TokenEstimationException(String message, Throwable cause) {
        super(message, cause);
    }

    public TokenEstimationException(String message) {
        super(message);
    }
}
