package com.codeagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAgentEmbeddingConfigTest {

    @Test
    void defaultsEmbeddingModeToLocal() {
        CodeAgentConfig config = new CodeAgentConfig();

        assertEquals("local", config.getEmbedding().getMode());
        assertNull(config.getEmbedding().getProvider());
    }

    @Test
    void loadsLegacyJsonWithoutEmbeddingSection(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, """
                {
                  "defaultProvider": "deepseek",
                  "providers": {
                    "deepseek": {"model": "deepseek-chat"}
                  },
                  "futureField": true
                }
                """);

        CodeAgentConfig config = CodeAgentConfig.load(file, Map.of());

        assertEquals("deepseek", config.getDefaultProvider());
        assertEquals("deepseek-chat", config.getProviders().get("deepseek").getModel());
        assertEquals("local", config.getEmbedding().getMode());
    }

    @Test
    void explicitJsonEmbeddingValuesOverrideEnvironment(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, """
                {
                  "embedding": {
                    "mode": "remote",
                    "provider": "glm",
                    "model": "embedding-3",
                    "baseUrl": "https://configured.example/v1",
                    "apiKey": "configured-secret"
                  }
                }
                """);

        CodeAgentConfig config = CodeAgentConfig.load(file, Map.of(
                "EMBEDDING_MODE", "off",
                "EMBEDDING_PROVIDER", "jina",
                "EMBEDDING_MODEL", "env-model",
                "EMBEDDING_BASE_URL", "https://env.example/v1",
                "EMBEDDING_API_KEY", "env-secret"));

        CodeAgentConfig.EmbeddingConfig embedding = config.getEmbedding();
        assertEquals("remote", embedding.getMode());
        assertEquals("glm", embedding.getProvider());
        assertEquals("embedding-3", embedding.getModel());
        assertEquals("https://configured.example/v1", embedding.getBaseUrl());
        assertEquals("configured-secret", embedding.getApiKey());
    }

    @Test
    void fillsMissingEmbeddingValuesFromEnvironment(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, "{}");

        CodeAgentConfig config = CodeAgentConfig.load(file, Map.of(
                "EMBEDDING_MODE", "remote",
                "EMBEDDING_PROVIDER", "jina",
                "EMBEDDING_MODEL", "jina-code-embeddings-1.5b",
                "EMBEDDING_BASE_URL", "https://api.jina.ai/v1",
                "EMBEDDING_API_KEY", "env-secret"));

        CodeAgentConfig.EmbeddingConfig embedding = config.getEmbedding();
        assertEquals("remote", embedding.getMode());
        assertEquals("jina", embedding.getProvider());
        assertEquals("jina-code-embeddings-1.5b", embedding.getModel());
        assertEquals("https://api.jina.ai/v1", embedding.getBaseUrl());
        assertEquals("env-secret", embedding.getApiKey());
    }

    @Test
    void roundTripsEmbeddingConfigurationWithoutEchoingKey(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("config.json");
        CodeAgentConfig config = new CodeAgentConfig();
        CodeAgentConfig.EmbeddingConfig embedding = config.getEmbedding();
        embedding.setMode("remote");
        embedding.setProvider("glm");
        embedding.setApiKey("do-not-echo-this-key");

        config.save(file);
        CodeAgentConfig restored = CodeAgentConfig.load(file, Map.of());

        assertEquals("remote", restored.getEmbedding().getMode());
        assertEquals("do-not-echo-this-key", restored.getEmbedding().getApiKey());
        assertFalse(restored.getEmbedding().toString().contains("do-not-echo-this-key"));
        assertTrue(restored.getEmbedding().toString().contains("***"));
    }
}
