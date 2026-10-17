package com.codeagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CodeAgentProviderConfigTest {
    @Test void saveFailureIsExplicitAndDoesNotEchoConfiguration(@TempDir Path directory) throws Exception {
        var config = CodeAgentConfig.load(directory.resolve("absent.json"), Map.of());
        config.getProviders().put("glm", new CodeAgentConfig.ProviderConfig("private-fixture", null, null));
        Path blocked = directory.resolve("not-a-directory");
        Files.writeString(blocked, "untouched");
        var error = assertThrows(RuntimeException.class, () -> config.save(blocked.resolve("config.json")));
        assertFalse(error.toString().contains("private-fixture"));
        assertEquals("untouched", Files.readString(blocked));
    }

    @Test void saveRefusesDamagedJson(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, "{broken-private-fixture");
        var captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalError = System.err;
        CodeAgentConfig config;
        try (var error = new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(error);
            config = CodeAgentConfig.load(file, Map.of());
        } finally { System.setErr(originalError); }
        assertFalse(captured.toString(java.nio.charset.StandardCharsets.UTF_8).contains("broken-private-fixture"));
        assertThrows(RuntimeException.class, () -> config.save(file));
        assertEquals("{broken-private-fixture", Files.readString(file));
    }

    @Test void saveMergesUnknownAndConcurrentUnrelatedChanges(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, "{\"future\":42,\"providers\":{\"glm\":{\"model\":\"old\",\"future\":true}}}");
        var config = CodeAgentConfig.load(file, Map.of());
        config.getProviders().get("glm").setModel("new");
        Files.writeString(file, "{\"future\":43,\"providers\":{\"glm\":{\"model\":\"old\",\"future\":true},\"step\":{\"model\":\"concurrent\"}}}");
        config.save(file);
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile());
        assertEquals(43, tree.path("future").asInt());
        assertTrue(tree.at("/providers/glm/future").asBoolean());
        assertEquals("concurrent", tree.at("/providers/step/model").asText());
        assertEquals("new", tree.at("/providers/glm/model").asText());
    }
    @Test void selectorOverridesJsonDefaultWithoutOverwritingModels(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, "{\"defaultProvider\":\"glm\",\"providers\":{\"step\":{\"model\":\"configured-model\"}}}");
        var config = CodeAgentConfig.load(file, Map.of("CODEAGENT_PROVIDER", " STEP ",
                "STEP_API_KEY", "fixture-key", "STEP_MODEL", "environment-model"));
        assertEquals("step", config.getDefaultProvider());
        assertTrue("fixture-key".equals(config.getApiKey("step")));
        assertEquals("configured-model", config.getModel("step"));
    }

    @Test void blankSelectorKeepsJsonDefault(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, "{\"defaultProvider\":\"deepseek\"}");
        assertEquals("deepseek", CodeAgentConfig.load(file, Map.of("CODEAGENT_PROVIDER", "")).getDefaultProvider());
    }

    @Test void environmentProviderFieldsUseInjectedMapAndAliases(@TempDir Path directory) {
        var config = CodeAgentConfig.load(directory.resolve("missing.json"), Map.of(
                "CODEAGENT_PROVIDER", "moonshot", "MOONSHOT_API_KEY", "fixture-moonshot",
                "MOONSHOT_MODEL", "fixture-model", "MOONSHOT_BASE_URL", "https://fixture.example/v1"));
        assertEquals("moonshot", config.getDefaultProvider());
        assertTrue("fixture-moonshot".equals(config.getApiKey("kimi")));
        assertEquals("fixture-model", config.getModel("kimi"));
        assertEquals("https://fixture.example/v1", config.getBaseUrl("kimi"));
    }

    @Test void blankInjectedKeyDoesNotReadRealProjectSecrets(@TempDir Path directory) {
        var config = CodeAgentConfig.load(directory.resolve("missing.json"), Map.of("DEEPSEEK_API_KEY", ""));
        assertTrue(config.getApiKey("deepseek") == null);
    }

    @Test void serializingConfigurationDoesNotPersistEnvironmentKeys(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config.json");
        var config = CodeAgentConfig.load(file, Map.of("CODEAGENT_PROVIDER", "deepseek",
                "DEEPSEEK_API_KEY", "environment-only-fixture"));
        config.save(file);
        String saved = Files.readString(file);
        assertFalse(saved.contains("environment-only-fixture"));
        assertFalse(saved.contains("environmentValues"));
        assertEquals("deepseek", CodeAgentConfig.load(file, Map.of()).getDefaultProvider());
    }
    @Test void blankCanonicalKeysBlockLegacyAliasSecrets(@TempDir Path directory) {
        var config = CodeAgentConfig.load(directory.resolve("missing.json"), Map.of(
                "KIMI_API_KEY", "", "MOONSHOT_API_KEY", "alias-fixture",
                "XFYUN_MAAS_API_KEY", "", "XFYUN_API_KEY", "alias-fixture"));
        assertTrue(config.getApiKey("kimi") == null);
        assertTrue(config.getApiKey("xfyun") == null);
    }

    @Test void environmentEmbeddingKeyIsUsedWithoutBeingSaved(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config.json");
        var config = CodeAgentConfig.load(file, Map.of("EMBEDDING_API_KEY", "embedding-environment-fixture"));
        assertTrue("embedding-environment-fixture".equals(config.getEmbedding().getApiKey()));
        config.save(file);
        assertFalse(Files.readString(file).contains("embedding-environment-fixture"));
        config.getEmbedding().setApiKey("explicit-json-fixture");
        config.save(file);
        var restored = CodeAgentConfig.load(file, Map.of("EMBEDDING_API_KEY", "other-environment-fixture"));
        assertTrue("explicit-json-fixture".equals(restored.getEmbedding().getApiKey()));
    }
}
