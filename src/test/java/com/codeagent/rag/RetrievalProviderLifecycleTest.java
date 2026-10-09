package com.codeagent.rag;

import com.codeagent.rag.embedding.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalProviderLifecycleTest {
    @Test void reconfigureDoesNotWaitForInferenceAndDefersClose(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Store.java"), "class Store { void saveData() {} }");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closes = new AtomicInteger();
        EmbeddingProvider provider = new EmbeddingProvider() {
            final EmbeddingSpaceDescriptor space = EmbeddingSpaceDescriptor.create("test", "test", "local", "1", 2, "mean", true, 1, 1);
            public String id() { return "test"; }
            public String modelId() { return "test"; }
            public EmbeddingSpaceDescriptor space() { return space; }
            public EmbeddingLocality locality() { return EmbeddingLocality.IN_PROCESS; }
            public List<float[]> embedAll(List<String> inputs) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return inputs.stream().map(s -> new float[]{1, 0}).toList();
            }
            public void close() { closes.incrementAndGet(); }
        };
        var pool = Executors.newFixedThreadPool(2);
        try (var index = new SqliteRetrievalIndex(root.resolve("index.db"));
             var service = new DefaultCodeRetrievalService(index, new EmbeddingResolution(Optional.of(provider), "test", false))) {
            new IndexCoordinator(index, Optional.empty()).refresh(new IndexRefreshRequest(root, false));
            var search = pool.submit(() -> service.search(new RetrievalRequest(root, "save data", 10, 16000, true, RetrievalIntent.CHUNKS)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var change = pool.submit(() -> service.reconfigureEmbedding(new EmbeddingResolution(Optional.empty(), "embedding_disabled", false)));
            try {
                change.get(1, TimeUnit.SECONDS);
                assertEquals(0, closes.get(), "active inference must retain provider");
            } finally { release.countDown(); }
            search.get(5, TimeUnit.SECONDS);
            assertEquals(1, closes.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
