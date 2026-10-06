package com.codeagent.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicalTextNormalizerTest {
    @Test
    void queryPreservesStandaloneLowercaseMethodNames() {
        LexicalTextNormalizer normalizer = new LexicalTextNormalizer();
        assertEquals("of", normalizer.normalizeQuery("of"));
        assertEquals("of", normalizer.normalizeQuery("of()"));
    }

    @Test
    void queryPreservesCapitalizedIdentifiersThatResembleFillers() {
        LexicalTextNormalizer normalizer = new LexicalTextNormalizer();
        for (String name : java.util.List.of("A", "Do", "Is", "The")) {
            assertTrue(java.util.Arrays.asList(normalizer.normalizeQuery(name).split(" ")).contains(name));
        }
        assertEquals("", normalizer.normalizeQuery("please how to 请问如何"));
    }

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
