package com.codeagent.llm;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.config.ProviderDescriptor;
import com.codeagent.config.ApiKeyValidation;
import java.util.List;

public class LlmClientFactory {

    private LlmClientFactory() {}

    public static List<ProviderDescriptor> providerDescriptors() {
        return ProviderCatalog.DESCRIPTORS;
    }

    public static LlmClient create(String provider, CodeAgentConfig config) {
        return create(provider, config, LlmClientOptions.DEFAULT);
    }

    public static LlmClient create(String provider, CodeAgentConfig config, LlmClientOptions options) {
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
        if (!ApiKeyValidation.isHeaderSafe(apiKey)) throw new IllegalArgumentException("Invalid API credential format");

        String model = firstConfigured(config.getModel(normalized),
                configuredProvider.equals(normalized) ? null : config.getModel(configuredProvider));
        String baseUrl = firstConfigured(config.getBaseUrl(normalized),
                configuredProvider.equals(normalized) ? null : config.getBaseUrl(configuredProvider));
        String loraId = firstConfigured(config.getLoraId(normalized),
                configuredProvider.equals(normalized) ? null : config.getLoraId(configuredProvider));

        return switch (normalized) {
            case "glm" -> new GLMClient(apiKey, model, options);
            case "deepseek" -> new DeepSeekClient(apiKey, model, options);
            case "hunyuan" -> new HunyuanClient(apiKey, model, baseUrl, options);
            case "step" -> new StepClient(apiKey, model, baseUrl, options);
            case "kimi" -> new KimiClient(apiKey, model, baseUrl, options);
            case "freellmapi" -> new FreeLlmApiClient(apiKey, model, baseUrl, options);
            case "xfyun" -> new XfyunMaaSClient(apiKey, model, baseUrl, loraId, options);
            case "agnes" -> new AgnesClient(apiKey, model, baseUrl, options);
            default -> null;
        };
    }

    public static LlmClient createFromConfig(CodeAgentConfig config) {
        return createFromConfig(config, LlmClientOptions.DEFAULT);
    }

    public static LlmClient createFromConfig(CodeAgentConfig config, LlmClientOptions options) {
        String selected = config.getDefaultProvider();
        String normalized = selected == null ? "" : normalizeProvider(selected);
        if (providerDescriptors().stream().noneMatch(descriptor -> descriptor.id().equals(normalized))) {
            throw new IllegalArgumentException("CODEAGENT_PROVIDER 必须是 glm/deepseek/hunyuan/step/kimi/freellmapi/xfyun/agnes");
        }
        LlmClient client = create(selected, config, options);
        if (client == null) {
            throw new IllegalStateException("所选 Provider " + normalized + " 缺少 API Key，请配置对应密钥");
        }
        return client;
    }

    private static String normalizeProvider(String provider) {
        return ProviderDescriptor.normalizeId(provider);
    }

    private static String firstConfigured(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}
