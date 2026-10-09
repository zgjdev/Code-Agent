package com.codeagent.memory;

import com.codeagent.rag.embedding.EmbeddingInputPolicy;
import com.codeagent.rag.embedding.EmbeddingSpaceDescriptor;
import com.codeagent.rag.embedding.InProcessQwen3EmbeddingProvider;
import com.codeagent.config.CodeAgentConfig;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("resource") // Providers and caches are in-memory method-scoped fixtures.
class MemoryEmbeddingCacheTest {
    @TempDir Path directory;

    @Test
    void missingModelKeepsLexicalRetrievalAvailableEvenWithRemoteRagConfig() {
        var config = new CodeAgentConfig.EmbeddingConfig();
        config.setMode("remote");
        config.setProvider("openai-compatible");
        config.setLocalModelDirectory("missing-model");
        var memory = new LongTermMemory(directory.resolve("memory").toFile());
        memory.store(new MemoryEntry("local", "Java 17", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 3));
        try (var cache = new MemoryEmbeddingCache(config, directory);
             var retriever = new MemoryRetriever(memory, cache, java.time.Clock.systemUTC())) {
            assertTrue(cache.embedQuery("Java").isEmpty());
            assertEquals(List.of("local"), retriever.retrieveLongTerm("Java", 5).stream()
                    .map(MemoryEntry::getId).toList());
        }
    }

    @Test
    void invalidConfiguredDirectoryFailsLazilyAndDisablesOnlySemantics() {
        var config = new CodeAgentConfig.EmbeddingConfig();
        config.setLocalModelDirectory("invalid\u0000directory");
        try (var cache = new MemoryEmbeddingCache(config, directory)) {
            assertNotNull(cache.embeddingSpaceId());
            assertTrue(cache.embedQuery("中文").isEmpty());
        }
    }

    @Test
    void defaultCacheUsesQwenMemorySpaceWithoutLoadingArtifacts() {
        try (var cache = new MemoryEmbeddingCache()) {
            var expected = EmbeddingSpaceDescriptor.create("local-qwen3",
                    "qwen3-embedding-0.6b-onnx-fp32", "in-process",
                    InProcessQwen3EmbeddingProvider.REVISION + ":fp32", 1024,
                    "last-token", true, 3, 1);
            assertEquals(expected.embeddingSpaceId(), cache.embeddingSpaceId());
        }
    }

    @Test
    void preparesTypedInputsAndRecomputesQueriesWhileCachingEntries() {
        var provider = new MemoryTestEmbeddingProvider().vector("中文", 1f, 0f);
        try (var cache = new MemoryEmbeddingCache(provider)) {
            var entry = new MemoryEntry("typed", "中文", MemoryEntry.MemoryType.FACT,
                    Map.of("scope", "global"), 2);
            cache.embedQuery("中文");
            cache.embedQuery("中文");
            cache.embeddingsFor(List.of(entry));
            cache.embeddingsFor(List.of(entry));
            var policy = new EmbeddingInputPolicy();
            assertEquals(List.of(policy.prepareQuery("中文"), policy.prepareQuery("中文"),
                    policy.prepareDocument("中文")), provider.inputs());
        }
    }

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
