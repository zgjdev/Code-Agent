package com.codeagent.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeIndexTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearRagDirectoryOverride() {
        System.clearProperty("codeagent.rag.dir");
    }

    @Test
    void testIndexNonExistentPath() {
        CodeIndex indexer = new CodeIndex(new FakeEmbeddingClient());
        CodeIndex.IndexResult result = indexer.index("/non/existent/path");
        assertEquals(0, result.chunkCount());
        assertTrue(result.message().contains("路径不存在"));
    }

    @Test
    void testIndexCurrentProject() {
        System.setProperty("codeagent.rag.dir", tempDir.toString());
        CodeIndex indexer = new CodeIndex(new FakeEmbeddingClient());

        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");

        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(result.message().contains("索引完成"));
    }

    @Test
    void reportsProgressThroughListener() {
        System.setProperty("codeagent.rag.dir", tempDir.toString());
        List<String> messages = new ArrayList<>();
        CodeIndex indexer = new CodeIndex(new FakeEmbeddingClient(), messages::add);

        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");

        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("🔍 开始索引")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("📁 发现")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("✅ 索引完成")));
    }

    private static final class FakeEmbeddingClient extends EmbeddingClient {
        private FakeEmbeddingClient() {
            super("zhipu", "test", "http://localhost", "test-key");
        }

        @Override
        public float[] embed(String text) throws IOException {
            int length = text == null ? 0 : text.length();
            return new float[]{1.0f, Math.max(1, length)};
        }
    }
}
