package com.codeagent.llm;

import com.codeagent.config.CodeAgentConfig;

public class LlmClientFactory {

    private LlmClientFactory() {}

    public static LlmClient create(String provider, CodeAgentConfig config) {
        if (provider == null) return null;

        String normalized = normalizeProvider(provider);
        String configuredProvider = provider.trim().toLowerCase(java.util.Locale.ROOT);
        String apiKey = config.getApiKey(normalized);
        if ((apiKey == null || apiKey.isBlank()) && !configuredProvider.equals(normalized)) {
            // Environment aliases are resolved by CodeAgentConfig; do not bypass an explicit blank canonical key.
            CodeAgentConfig.ProviderConfig aliasConfig = config.getProviders().get(configuredProvider);
            apiKey = aliasConfig == null ? null : aliasConfig.getApiKey();
        }
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String model = firstConfigured(config.getModel(normalized),
                configuredProvider.equals(normalized) ? null : config.getModel(configuredProvider));
        String baseUrl = firstConfigured(config.getBaseUrl(normalized),
                configuredProvider.equals(normalized) ? null : config.getBaseUrl(configuredProvider));
        String loraId = firstConfigured(config.getLoraId(normalized),
                configuredProvider.equals(normalized) ? null : config.getLoraId(configuredProvider));

        return switch (normalized) {
            case "glm" -> new GLMClient(apiKey, model);
            case "deepseek" -> new DeepSeekClient(apiKey, model);
            case "hunyuan" -> new HunyuanClient(apiKey, model, baseUrl);
            case "step" -> new StepClient(apiKey, model, baseUrl);
            case "kimi" -> new KimiClient(apiKey, model, baseUrl);
            case "freellmapi" -> new FreeLlmApiClient(apiKey, model, baseUrl);
            case "xfyun" -> new XfyunMaaSClient(apiKey, model, baseUrl, loraId);
            case "agnes" -> new AgnesClient(apiKey, model, baseUrl);
            default -> null;
        };
    }

    public static LlmClient createFromConfig(CodeAgentConfig config) {
        String selected = config.getDefaultProvider();
        String normalized = selected == null ? "" : normalizeProvider(selected);
        if (!java.util.Set.of("glm", "deepseek", "hunyuan", "step", "kimi", "freellmapi", "xfyun", "agnes").contains(normalized)) {
            throw new IllegalArgumentException("CODEAGENT_PROVIDER 必须是 glm/deepseek/hunyuan/step/kimi/freellmapi/xfyun/agnes");
        }
        LlmClient client = create(selected, config);
        if (client == null) {
            throw new IllegalStateException("所选 Provider " + normalized + " 缺少 API Key，请配置对应密钥");
        }
        return client;
    }

    private static String normalizeProvider(String provider) {
        String normalized = provider.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "stepfun", "step-fun" -> "step";
            case "hy4", "hy4-preview", "tokenhub", "tencent-hunyuan", "tencent_hunyuan" -> "hunyuan";
            case "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            case "free-llm-api", "free_llm_api", "freellm", "free-llm" -> "freellmapi";
            case "xfyun-maas", "xfyun_maas", "iflytek", "iflytek-maas", "iflytek_maas", "maas" -> "xfyun";
            case "agnes-ai", "agnes_ai", "sapiens", "sapiens-ai", "sapiens_ai" -> "agnes";
            default -> normalized;
        };
    }

    private static String firstConfigured(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}
