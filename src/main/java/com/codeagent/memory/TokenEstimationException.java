package com.codeagent.memory;

/** Raised when a model-visible content block cannot be priced safely. */
public class TokenEstimationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TokenEstimationException(String message, Throwable cause) {
        super(message, cause);
    }

    public TokenEstimationException(String message) {
        super(message);
    }
}
