package com.codeagent.llm;

import java.io.IOException;

final class LlmStreamingApiException extends IOException {
    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    LlmStreamingApiException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    boolean retryable() {
        return retryable;
    }
}
