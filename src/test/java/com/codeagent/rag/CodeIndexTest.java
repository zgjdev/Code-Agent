package com.codeagent.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("deprecation") // Verifies the intentionally retained EmbeddingClient compatibility adapter.
class CodeIndexTest {
    @Test void closesOwnedProviderExactlyOnceButDoesNotCloseBorrowedProvider() {
        var ownedClient = new FakeEmbeddingClient();
        var borrowedClient = new FakeEmbeddingClient();
        try (var owned = new CodeIndex(Optional.of(new TrackingProvider(ownedClient)), CodeIndex.ProgressListener.noop(), true);
             var borrowed = new CodeIndex(Optional.of(new TrackingProvider(borrowedClient)), CodeIndex.ProgressListener.noop())) {
            owned.close(); owned.close(); borrowed.close();
            assertThrows(IllegalStateException.class, () -> owned.index("/non/existent/path"));
        }
        assertEquals(1, ownedClient.closes);
        assertEquals(0, borrowedClient.closes);
        borrowedClient.close();
    }

    @TempDir
    Path tempDir;

    @AfterEach
    void clearRagDirectoryOverride() {
        System.clearProperty("codeagent.rag.dir");
    }

    @Test
    void testIndexNonExistentPath() {
        try (FakeEmbeddingClient client = new FakeEmbeddingClient();
             CodeIndex indexer = new CodeIndex(client)) {
            CodeIndex.IndexResult result = indexer.index("/non/existent/path");
            assertEquals(0, result.chunkCount());
            assertTrue(result.message().contains("路径不存在"));
        }
    }

    @Test
    void testIndexCurrentProject() {
        System.setProperty("codeagent.rag.dir", tempDir.toString());
        try (FakeEmbeddingClient client = new FakeEmbeddingClient();
             CodeIndex indexer = new CodeIndex(client)) {
            CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");
            assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
            assertTrue(result.message().contains("索引完成"));
        }
    }

    @Test
    void reportsProgressThroughListener() {
        System.setProperty("codeagent.rag.dir", tempDir.toString());
        List<String> messages = new ArrayList<>();
        try (FakeEmbeddingClient client = new FakeEmbeddingClient();
             CodeIndex indexer = new CodeIndex(client, messages::add)) {
            CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");
            assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
            assertTrue(messages.stream().anyMatch(message -> message.startsWith("🔍 开始索引")));
            assertTrue(messages.stream().anyMatch(message -> message.startsWith("📁 发现")));
            assertTrue(messages.stream().anyMatch(message -> message.startsWith("✅ 索引完成")));
        }
    }

    private static final class FakeEmbeddingClient extends EmbeddingClient {
        int closes;
        @Override public void close() { closes++; }
        private FakeEmbeddingClient() {
            super("zhipu", "test", "http://localhost", "test-key");
        }

        @Override
        public float[] embed(String text) throws IOException {
            int length = text == null ? 0 : text.length();
            return new float[]{1.0f, Math.max(1, length)};
        }
    }

    private static final class TrackingProvider implements com.codeagent.rag.embedding.EmbeddingProvider {
        private final FakeEmbeddingClient client;
        TrackingProvider(FakeEmbeddingClient client) { this.client = client; }
        public String id() { return "tracking"; }
        public String modelId() { return "test"; }
        public com.codeagent.rag.embedding.EmbeddingSpaceDescriptor space() { throw new UnsupportedOperationException(); }
        public com.codeagent.rag.embedding.EmbeddingLocality locality() { return com.codeagent.rag.embedding.EmbeddingLocality.IN_PROCESS; }
        public List<float[]> embedAll(List<String> inputs) { throw new UnsupportedOperationException(); }
        public void close() { client.close(); }
    }
}
