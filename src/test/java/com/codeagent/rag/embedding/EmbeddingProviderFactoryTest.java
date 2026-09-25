package com.codeagent.rag.embedding;

import com.codeagent.config.CodeAgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingProviderFactoryTest {

    @Test
    void defaultsToLazyLocalProvider(@TempDir Path tempDir) throws Exception {
        Path project = project(tempDir);

        EmbeddingResolution resolution = new EmbeddingProviderFactory().resolve(
                new CodeAgentConfig(), project, null);

        assertTrue(resolution.provider().isPresent());
        assertEquals(EmbeddingLocality.IN_PROCESS, resolution.provider().orElseThrow().locality());
        assertFalse(resolution.consentRequired());
    }

    @Test
    void offReturnsNoProvider(@TempDir Path tempDir) throws Exception {
        CodeAgentConfig config = new CodeAgentConfig();
        config.getEmbedding().setMode("off");

        EmbeddingResolution resolution = new EmbeddingProviderFactory().resolve(
                config, project(tempDir), null);

        assertTrue(resolution.provider().isEmpty());
        assertEquals("embedding_disabled", resolution.reason());
    }

    @Test
    void unsupportedLegacyValueUsesGenericFallbackWithoutHttp(@TempDir Path tempDir) throws Exception {
        CodeAgentConfig config = new CodeAgentConfig();
        config.getEmbedding().setMode("ollama");

        EmbeddingResolution resolution = new EmbeddingProviderFactory().resolve(
                config, project(tempDir), null);

        assertTrue(resolution.provider().isPresent());
        assertEquals(EmbeddingLocality.IN_PROCESS, resolution.provider().orElseThrow().locality());
        assertEquals("unsupported_embedding_provider", resolution.reason());
    }

    @Test
    void remoteWithoutMatchingCapabilityDoesNotCreateProvider(@TempDir Path tempDir) throws Exception {
        CodeAgentConfig config = remoteConfig();

        EmbeddingResolution resolution = new EmbeddingProviderFactory().resolve(
                config, project(tempDir), null);

        assertTrue(resolution.provider().isEmpty());
        assertTrue(resolution.consentRequired());
        assertEquals("remote_embedding_consent_required", resolution.reason());
    }

    @Test
    void matchingCapabilityEnablesRemoteProvider(@TempDir Path tempDir) throws Exception {
        Path project = project(tempDir);
        CodeAgentConfig config = remoteConfig();
        RemoteEmbeddingConsentRequest request = RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-3", "https://open.bigmodel.cn/api/paas/v4", 1);
        RemoteEmbeddingCapability capability = new RemoteEmbeddingCapability(request.toConsent());

        EmbeddingResolution resolution = new EmbeddingProviderFactory().resolve(config, project, capability);

        assertTrue(resolution.provider().isPresent());
        assertEquals(EmbeddingLocality.REMOTE, resolution.provider().orElseThrow().locality());
    }

    private static CodeAgentConfig remoteConfig() {
        CodeAgentConfig config = new CodeAgentConfig();
        config.getEmbedding().setMode("remote");
        config.getEmbedding().setProvider("glm");
        config.getEmbedding().setApiKey("key");
        return config;
    }

    private static Path project(Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        return project;
    }
}
