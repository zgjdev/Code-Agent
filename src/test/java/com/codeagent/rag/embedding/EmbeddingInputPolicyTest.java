package com.codeagent.rag.embedding;

import com.codeagent.memory.MemoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingInputPolicyTest {

    @Test
    void addsStableQueryAndDocumentPrefixes() {
        EmbeddingInputPolicy policy = new EmbeddingInputPolicy();

        assertEquals("为这个句子生成表示以用于检索相关文章：上下文压缩", policy.prepareQuery("  上下文压缩  "));
        assertEquals("代码文档：class Agent {}", policy.prepareDocument(" class Agent {} "));
    }

    @Test
    void splitsLongDocumentsOnLinesWithinConservativeBudget() {
        EmbeddingInputPolicy policy = new EmbeddingInputPolicy();
        String longMethod = String.join("\n", java.util.Collections.nCopies(300,
                "处理上下文窗口并保留最近的用户消息"));

        List<String> parts = policy.prepareDocumentParts(longMethod);

        assertTrue(parts.size() > 1);
        assertTrue(parts.stream().allMatch(part -> MemoryEntry.estimateTokens(part) <= 384));
        assertTrue(parts.stream().allMatch(part -> part.startsWith("代码文档：")));
        assertFalse(parts.get(0).equals(parts.get(1)));
    }

    @Test
    void overlapsTwoLinesAcrossNormalPartBoundaries() {
        EmbeddingInputPolicy policy = new EmbeddingInputPolicy();
        String longMethod = java.util.stream.IntStream.range(0, 160)
                .mapToObj(i -> "line-" + i + " process conversation context")
                .collect(java.util.stream.Collectors.joining("\n"));

        List<String> parts = policy.prepareDocumentParts(longMethod);
        String[] first = parts.get(0).split("\n");
        String[] second = parts.get(1).split("\n");

        assertEquals(first[first.length - 2], second[1]);
        assertEquals(first[first.length - 1], second[2]);
        assertTrue(parts.stream().allMatch(part -> MemoryEntry.estimateTokens(part) <= 384));
    }
}
