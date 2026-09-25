package com.codeagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryEmbeddingCacheTest {

    @Test
    void cachesEntryEmbeddingByIdContentAndSpace() {
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector("用户偏好 Java", 1f, 0f);
        MemoryEmbeddingCache cache = new MemoryEmbeddingCache(provider);
        MemoryEntry entry = new MemoryEntry(
                "f1", "用户偏好 Java", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 4);

        cache.embeddingsFor(List.of(entry));
        cache.embeddingsFor(List.of(entry));

        assertEquals(1, provider.calls("用户偏好 Java"));
        assertEquals(1, cache.cachedEntryCount());
    }

    @Test
    void contentChangeWithSameIdProducesNewEmbedding() {
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector("用户偏好 Java", 1f, 0f)
                .vector("用户偏好 Python", 0f, 1f);
        MemoryEmbeddingCache cache = new MemoryEmbeddingCache(provider);
        MemoryEntry java = new MemoryEntry(
                "f1", "用户偏好 Java", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 4);
        MemoryEntry python = new MemoryEntry(
                "f1", "用户偏好 Python", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 4);

        cache.embeddingsFor(List.of(java));
        cache.embeddingsFor(List.of(python));

        assertEquals(1, provider.calls("用户偏好 Java"));
        assertEquals(1, provider.calls("用户偏好 Python"));
        assertEquals(2, cache.cachedEntryCount());
    }

    @Test
    void embeddingFailureReturnsNoNewVectors() {
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider().fail(true);
        MemoryEmbeddingCache cache = new MemoryEmbeddingCache(provider);
        MemoryEntry entry = new MemoryEntry(
                "f1", "用户偏好 Java", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 4);

        assertTrue(cache.embedQuery("Java").isEmpty());
        assertTrue(cache.embeddingsFor(List.of(entry)).isEmpty());
        assertEquals(0, cache.cachedEntryCount());
    }
}
