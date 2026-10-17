package com.codeagent.llm;

import com.codeagent.config.CodeAgentConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmClientFactoryTest {
    @Test void invalidHeaderCredentialFailsBeforeCreatingClientWithoutEchoingValue() {
        var config = new CodeAgentConfig();
        config.getProviders().put("step", new CodeAgentConfig.ProviderConfig("fixture\nprivate", null, null));
        var failure = assertThrows(IllegalArgumentException.class, () -> LlmClientFactory.create("step", config));
        org.junit.jupiter.api.Assertions.assertFalse(failure.toString().contains("fixture"));
    }

    @Test
    void createsGlm5vTurboClientWithMultimodalEndpoint() {
        CodeAgentConfig config = new CodeAgentConfig();
        config.getProviders().put("glm",
                new CodeAgentConfig.ProviderConfig("test-glm-key", null, "glm-5v-turbo"));

        LlmClient client = LlmClientFactory.create("glm", config);

        GLMClient glmClient = assertInstanceOf(GLMClient.class, client);
        assertEquals("glm", glmClient.getProviderName());
        assertEquals("glm-5v-turbo", glmClient.getModelName());
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", glmClient.getApiUrl());
    }

    @Test
    void createsStepClientFromConfiguredProvider() {
        CodeAgentConfig config = new CodeAgentConfig();
        config.getProviders().put("step",
                new CodeAgentConfig.ProviderConfig("test-step-key", null, "step-3.5-flash-2603"));

        LlmClient client = LlmClientFactory.create("step", config);

        StepClient stepClient = assertInstanceOf(StepClient.class, client);
        assertEquals("step", stepClient.getProviderName());
        assertEquals("step-3.5-flash-2603", stepClient.getModelName());
        assertEquals(256_000, stepClient.maxContextWindow());
        assertEquals(expectedStepChatUrl(config.getBaseUrl("step")), stepClient.getApiUrl());
    }

    @Test
    void createsHunyuanClientFromHy4AliasAndConfiguredProvider() {
        CodeAgentConfig config = new CodeAgentConfig();
        config.getProviders().put("hunyuan",
                new CodeAgentConfig.ProviderConfig(
                        "test-hunyuan-key",
                        "https://tokenhub.tencentmaas.com/v1",
                        "hy4-preview"));

        LlmClient client = LlmClientFactory.create("hy4", config);
        LlmClient previewAliasClient = LlmClientFactory.create("hy4-preview", config);

        HunyuanClient hunyuanClient = assertInstanceOf(HunyuanClient.class, client);
        assertInstanceOf(HunyuanClient.class, previewAliasClient);
        assertEquals("hunyuan", hunyuanClient.getProviderName());
        assertEquals("hy4-preview", hunyuanClient.getModelName());
        assertEquals("https://tokenhub.tencentmaas.com/v1/chat/completions", hunyuanClient.getApiUrl());
        assertEquals(1_000_000, hunyuanClient.maxContextWindow());
        assertEquals(false, hunyuanClient.supportsImageInput());
        assertEquals(true, hunyuanClient.supportsPromptCaching());
    }

    @Test
    void createsStepClientFromStepfunAliasAndCustomBaseUrl() {
        CodeAgentConfig config = new CodeAgentConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("step",
                new CodeAgentConfig.ProviderConfig(
                        "test-step-key",
                        "https://api.stepfun.com/step_plan/v1",
                        "step-router-v1"));

        LlmClient client = LlmClientFactory.create("stepfun", config);

        StepClient stepClient = assertInstanceOf(StepClient.class, client);
        assertEquals("step-router-v1", stepClient.getModelName());
        assertEquals("https://api.stepfun.com/step_plan/v1/chat/completions", stepClient.getApiUrl());
    }

    @Test
    void createsKimiClientFromMoonshotAliasAndCustomBaseUrl() {
        CodeAgentConfig config = new CodeAgentConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("kimi",
                new CodeAgentConfig.ProviderConfig(
                        "test-kimi-key",
                        "https://api.moonshot.ai/v1",
                        "kimi-k2.6"));

        LlmClient client = LlmClientFactory.create("moonshot", config);

        KimiClient kimiClient = assertInstanceOf(KimiClient.class, client);
        assertEquals("kimi", kimiClient.getProviderName());
        assertEquals("kimi-k2.6", kimiClient.getModelName());
        assertEquals(256_000, kimiClient.maxContextWindow());
    }

    @Test
    void createsFreeLlmApiClientFromConfiguredProvider() {
        CodeAgentConfig config = new CodeAgentConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("freellmapi",
                new CodeAgentConfig.ProviderConfig(
                        "test-free-key",
                        "http://localhost:5173/v1",
                        "auto"));

        LlmClient client = LlmClientFactory.create("free-llm-api", config);

        FreeLlmApiClient freeClient = assertInstanceOf(FreeLlmApiClient.class, client);
        assertEquals("freellmapi", freeClient.getProviderName());
        assertEquals("auto", freeClient.getModelName());
        assertEquals("http://localhost:5173/v1/chat/completions", freeClient.getApiUrl());
        assertEquals(128_000, freeClient.maxContextWindow());
    }

    @Test
    void createsXfyunMaaSClientFromAliasAndConfiguredProvider() {
        CodeAgentConfig config = new CodeAgentConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("xfyun",
                new CodeAgentConfig.ProviderConfig(
                        "test-xfyun-key",
                        "https://maas-api.cn-huabei-1.xf-yun.com/v2",
                        "Qwen3.6-35B-A3B"));
        config.getProviders().get("xfyun").setLoraId("0");

        LlmClient client = LlmClientFactory.create("maas", config);

        XfyunMaaSClient xfyunClient = assertInstanceOf(XfyunMaaSClient.class, client);
        assertEquals("xfyun", xfyunClient.getProviderName());
        assertEquals("Qwen3.6-35B-A3B", xfyunClient.getModelName());
        assertEquals("https://maas-api.cn-huabei-1.xf-yun.com/v2/chat/completions", xfyunClient.getApiUrl());
        assertEquals(128_000, xfyunClient.maxContextWindow());
        assertEquals(false, xfyunClient.supportsTools());
    }

    @Test
    void createsAgnesClientFromAliasAndConfiguredProvider() {
        CodeAgentConfig config = new CodeAgentConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("agnes",
                new CodeAgentConfig.ProviderConfig(
                        "test-agnes-key",
                        "https://apihub.agnes-ai.com/v1",
                        "agnes-2.0-flash"));

        LlmClient client = LlmClientFactory.create("agnes-ai", config);

        AgnesClient agnesClient = assertInstanceOf(AgnesClient.class, client);
        assertEquals("agnes", agnesClient.getProviderName());
        assertEquals("agnes-2.0-flash", agnesClient.getModelName());
        assertEquals("https://apihub.agnes-ai.com/v1/chat/completions", agnesClient.getApiUrl());
        assertEquals(1_000_000, agnesClient.maxContextWindow());
    }

    @Test
    void returnsNullForUnknownProvider() {
        CodeAgentConfig config = new CodeAgentConfig();
        config.getProviders().put("unknown", new CodeAgentConfig.ProviderConfig("test-key", null, "unknown-model"));

        assertNull(LlmClientFactory.create("unknown", config));
    }


    @Test
    void selectedProviderNeverFallsBackToAnotherAvailableKey() {
        CodeAgentConfig config = new CodeAgentConfig() {
            @Override public String getApiKey(String provider) {
                return "glm".equals(provider) ? "fixture-glm-key" : null;
            }
        };
        config.setDefaultProvider("deepseek");
        assertThrows(IllegalStateException.class, () -> LlmClientFactory.createFromConfig(config));
    }

    @Test
    void unknownSelectedProviderIsRejectedWithoutEchoingItsValue() {
        CodeAgentConfig config = new CodeAgentConfig() {
            @Override public String getApiKey(String provider) { return "fixture-key"; }
        };
        config.setDefaultProvider("invalid-sensitive-value");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> LlmClientFactory.createFromConfig(config));
        assertEquals(false, failure.getMessage().contains("invalid-sensitive-value"));
    }

    @Test
    void choosesConfiguredProviderEvenWhenOtherKeysAreAvailable() {
        CodeAgentConfig config = new CodeAgentConfig();
        config.setDefaultProvider("step");
        config.getProviders().put("glm", new CodeAgentConfig.ProviderConfig("fixture-glm", null, null));
        config.getProviders().put("step", new CodeAgentConfig.ProviderConfig("fixture-step", null, "step-3.5-flash"));
        assertInstanceOf(StepClient.class, LlmClientFactory.createFromConfig(config));
    }

    @Test
    void selectedAliasCannotBypassBlankCanonicalKey(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        for (String alias : java.util.List.of("moonshot", "xfyun_maas")) {
            var config = CodeAgentConfig.load(directory.resolve("missing.json"), java.util.Map.of(
                    "CODEAGENT_PROVIDER", alias, "KIMI_API_KEY", "", "MOONSHOT_API_KEY", "alias-fixture",
                    "XFYUN_MAAS_API_KEY", "", "XFYUN_API_KEY", "alias-fixture"));
            assertThrows(IllegalStateException.class, () -> LlmClientFactory.createFromConfig(config));
        }
    }

    @Test
    void explicitAliasJsonKeyRemainsAvailable(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        var config = CodeAgentConfig.load(directory.resolve("missing.json"), java.util.Map.of(
                "CODEAGENT_PROVIDER", "moonshot", "KIMI_API_KEY", ""));
        config.getProviders().put("moonshot", new CodeAgentConfig.ProviderConfig("explicit-fixture", null, null));
        assertInstanceOf(KimiClient.class, LlmClientFactory.createFromConfig(config));
    }

    private static String expectedStepChatUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank()
                ? baseUrl.trim()
                : "https://api.stepfun.com/v1";
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }
}
