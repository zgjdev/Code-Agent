package com.codeagent.llm;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ContextWindowExceededExceptionTest {
    @Test
    void isAnIoExceptionForExistingClientContracts() {
        assertInstanceOf(IOException.class, new ContextWindowExceededException("overflow"));
    }
}
