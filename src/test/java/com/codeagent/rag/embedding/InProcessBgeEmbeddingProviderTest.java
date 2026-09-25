package com.codeagent.rag.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class InProcessBgeEmbeddingProviderTest {

    @Test
    void lazilyCreatesOneSharedEngineAndReturnsDefensiveVectors() throws Exception {
        AtomicInteger creations = new AtomicInteger();
        InProcessBgeEmbeddingProvider provider = new InProcessBgeEmbeddingProvider(() -> {
            creations.incrementAndGet();
            return text -> new float[]{text.length(), 1f};
        }, 2, "test-artifact");

        assertEquals(0, creations.get());
        List<float[]> vectors = provider.embedAll(List.of("a", "bb"));

        assertEquals(1, creations.get());
        assertEquals(2, vectors.size());
        assertEquals(1f, vectors.get(0)[0]);
        vectors.get(0)[0] = 99f;
        assertNotEquals(vectors.get(0)[0], provider.embedAll(List.of("a")).get(0)[0]);
        assertEquals(EmbeddingLocality.IN_PROCESS, provider.locality());
    }

    @Test
    void descriptorUsesBundledModelIdentity() {
        InProcessBgeEmbeddingProvider provider = new InProcessBgeEmbeddingProvider(
                () -> text -> new float[512], 512, "1.18.0-beta28");

        assertEquals("local-bge", provider.id());
        assertEquals("bge-small-zh-v1.5-q", provider.modelId());
        assertEquals(512, provider.space().dimension());
        assertEquals("in-process", provider.space().endpointFingerprint());
    }

    @Test
    void bundledModelProducesDeterministicChineseAndEnglishVectors() throws Exception {
        try (InProcessBgeEmbeddingProvider provider = new InProcessBgeEmbeddingProvider()) {
            List<float[]> first = provider.embedAll(List.of("上下文压缩", "context compaction"));
            List<float[]> second = provider.embedAll(List.of("上下文压缩", "context compaction"));

            assertEquals(512, first.get(0).length);
            assertEquals(512, first.get(1).length);
            assertArrayEquals(first.get(0), second.get(0));
            assertArrayEquals(first.get(1), second.get(1));
        }
    }
}
