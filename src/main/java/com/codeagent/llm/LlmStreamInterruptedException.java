package com.codeagent.llm;

import java.io.IOException;

final class LlmStreamInterruptedException extends IOException {
    private static final long serialVersionUID = 1L;

    LlmStreamInterruptedException(String message) {
        super(message);
    }
}
