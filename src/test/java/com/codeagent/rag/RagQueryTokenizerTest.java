package com.codeagent.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagQueryTokenizerTest {
    @Test
    void returnsAtMostThreeIdentifierTokensAndSkipsNaturalLanguageWords() {
        assertEquals(java.util.List.of("ContextManager", "compactHistory", "session_id"),
                new RagQueryTokenizer().identifiers(
                        "请查找 ContextManager 的 compactHistory 和 session_id then ignoredToken"));
    }
}
