package com.codeagent.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingClientTest {

    @Test
    void testDefaultConfiguration() {
        EmbeddingClient client = new EmbeddingClient();
        assertEquals("local-bge", client.getProvider());
        assertEquals("bge-small-zh-v1.5-q", client.getModel());
    }

    @Test
    void testCustomConfiguration() {
        EmbeddingClient client = new EmbeddingClient("zhipu", "embedding-3",
                "https://open.bigmodel.cn/api/paas/v4", "test-key");
        assertEquals("zhipu", client.getProvider());
        assertEquals("embedding-3", client.getModel());
    }

    @Test
    void rejectsRemovedOllamaProvider() {
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingClient(
                "ollama", "nomic-embed-text", "http://localhost:11434", ""));
    }

    @Test
    void testEmptyInputReturnsEmptyArray() throws Exception {
        EmbeddingClient client = new EmbeddingClient();
        assertEquals(0, client.embed("").length);
        assertEquals(0, client.embed(null).length);
    }
}
