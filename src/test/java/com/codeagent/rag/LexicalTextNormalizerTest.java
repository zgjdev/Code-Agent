package com.codeagent.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicalTextNormalizerTest {
    @Test
    void normalizesChineseCamelCaseSnakeCaseAndDeduplicates() {
        String normalized = new LexicalTextNormalizer().normalize(
                "上下文压缩 contextWindow context_window AND \"contextWindow\"");
        assertTrue(normalized.contains("上下文"));
        assertTrue(normalized.contains("context"));
        assertTrue(normalized.contains("window"));
        assertEquals(1, java.util.Arrays.stream(normalized.split(" "))
                .filter("contextWindow"::equals).count());
    }
}
