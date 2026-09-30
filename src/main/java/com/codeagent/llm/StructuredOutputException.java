package com.codeagent.llm;

import java.io.IOException;

/** Raised after bounded structured-output repair attempts are exhausted. */
public final class StructuredOutputException extends IOException {
    private static final long serialVersionUID = 1L;

    private final LlmClient.ChatResponse response;

    StructuredOutputException(String message, Throwable cause, LlmClient.ChatResponse response) {
        super(message, cause);
        this.response = response;
    }

    public LlmClient.ChatResponse response() {
        return response;
    }
}
