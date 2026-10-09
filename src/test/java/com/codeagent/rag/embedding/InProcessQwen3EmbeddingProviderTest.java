package com.codeagent.rag.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InProcessQwen3EmbeddingProviderTest {
    @TempDir Path directory;
    @Test void memoryInstructionHasItsOwnSpaceAndLeavesRagContractUnchanged() {
        var policy = new EmbeddingInputPolicy();
        assertEquals("Instruct: " + Qwen3OnnxEngine.MEMORY_INSTRUCTION + "\nQuery: 语言偏好",
                Qwen3OnnxEngine.adapt(policy.prepareQuery("语言偏好"), Qwen3OnnxEngine.MEMORY_INSTRUCTION));
        assertEquals("用户偏好中文",
                Qwen3OnnxEngine.adapt(policy.prepareDocument("用户偏好中文"), Qwen3OnnxEngine.MEMORY_INSTRUCTION));
        try (var memory = InProcessQwen3EmbeddingProvider.forMemory(directory, "missing");
             var code = new InProcessQwen3EmbeddingProvider(directory)) {
            assertEquals(1024, memory.space().dimension());
            assertEquals(3, memory.space().preprocessingVersion());
            assertEquals(2, code.space().preprocessingVersion());
            assertNotEquals(memory.space().embeddingSpaceId(), code.space().embeddingSpaceId());
            assertThrows(EmbeddingException.class, () -> memory.embedAll(List.of(policy.prepareQuery("语言偏好"))));
        }
    }
    @Test void lazyProviderUsesDistinctSpaceAndClosesOnce() throws Exception {
        var creations = new AtomicInteger(); var closes = new AtomicInteger();
        float[] vector = new float[1024]; vector[0] = 1;
        var provider = new InProcessQwen3EmbeddingProvider(directory, () -> {
            creations.incrementAndGet();
            return new InProcessQwen3EmbeddingProvider.EmbeddingEngine() {
                public float[] embed(String text) { return vector; }
                public void close() { closes.incrementAndGet(); }
            };
        });
        assertEquals(0, creations.get());
        assertEquals("local-qwen3", provider.id());
        assertEquals(1024, provider.space().dimension());
        try (var bge = new InProcessBgeEmbeddingProvider()) { assertNotEquals(bge.space().embeddingSpaceId(), provider.space().embeddingSpaceId()); }
        var input = new EmbeddingInputPolicy().prepareQuery("恢复执行");
        var result = provider.embedAll(List.of(input, input)); result.get(0)[0] = 0;
        assertEquals(1, result.get(1)[0]); assertEquals(1, vector[0]); assertEquals(1, creations.get());
        provider.close(); provider.close(); assertEquals(1, closes.get());
        assertThrows(EmbeddingException.class, () -> provider.embedAll(List.of(input)));
    }
    @Test void initializationFailureIsCachedUntilReconfigured() {
        var attempts = new AtomicInteger();
        try (var provider = new InProcessQwen3EmbeddingProvider(directory, () -> { attempts.incrementAndGet(); throw new Exception("missing artifact"); })) {
            for (int i = 0; i < 3; i++) assertThrows(EmbeddingException.class,
                    () -> provider.embedAll(List.of(new EmbeddingInputPolicy().prepareDocument("class A {}"))));
            assertEquals(1, attempts.get());
        }
    }
    @Test void missingArtifactsFailLazilyWithoutNetwork() {
        try (var provider = new InProcessQwen3EmbeddingProvider(directory)) {
            assertEquals(1024, provider.space().dimension());
            assertThrows(EmbeddingException.class, () -> provider.embedAll(List.of(new EmbeddingInputPolicy().prepareQuery("恢复执行"))));
        }
    }
    @Test void invalidVectorsCannotBePersisted() {
        for (float[] invalid : List.of(new float[512], new float[1024], new float[]{Float.NaN})) {
            try (var provider = new InProcessQwen3EmbeddingProvider(directory, () -> text -> invalid)) {
                assertThrows(EmbeddingException.class, () -> provider.embedAll(List.of("input")));
            }
        }
    }
    @Test void fixedInputAndLastActiveTokenContracts() {
        var policy = new EmbeddingInputPolicy();
        assertEquals("Instruct: " + Qwen3OnnxEngine.INSTRUCTION + "\nQuery: 恢复执行", Qwen3OnnxEngine.adapt(policy.prepareQuery("恢复执行")));
        assertEquals("class A {}", Qwen3OnnxEngine.adapt(policy.prepareDocument("class A {}")));
        assertThrows(IllegalArgumentException.class, () -> Qwen3OnnxEngine.adapt("untyped"));
        assertArrayEquals(new float[]{.6f, .8f}, Qwen3OnnxEngine.pool(new float[][]{{3,4},{0,0}}, new long[]{1,0}), 1e-6f);
        assertThrows(IllegalArgumentException.class, () -> Qwen3OnnxEngine.pool(new float[][]{{0,0}}, new long[]{1}));
        assertThrows(IllegalArgumentException.class, () -> Qwen3OnnxEngine.pool(new float[][]{{1,2}}, new long[]{0}));
    }
}
