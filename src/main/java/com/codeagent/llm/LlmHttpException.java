package com.codeagent.llm;

import java.io.IOException;

final class LlmHttpException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String retryAfter;

    LlmHttpException(int statusCode, String retryAfter, String message) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    int statusCode() {
        return statusCode;
    }

    String retryAfter() {
        return retryAfter;
    }
}
