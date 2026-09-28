package com.codeagent.llm;

import java.io.IOException;

/** Provider rejected a request because its context window was exceeded. */
public class ContextWindowExceededException extends IOException {
    private static final long serialVersionUID = 1L;

    public ContextWindowExceededException(String message) {
        super(message);
    }

    public ContextWindowExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
