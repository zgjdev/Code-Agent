package com.codeagent.config;

import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.Comparator;

/** Provider metadata supplied by the client factory, with no dependency on clients or UI. */
public record ProviderDescriptor(String id, String displayName, String defaultModel, String defaultBaseUrl,
                                 EndpointPolicy endpointPolicy, boolean requiresKey, boolean supportsLoraId,
                                 Map<String, String> modelEndpointPrefixes) {
    public enum EndpointPolicy { FIXED, EDITABLE }
    public ProviderDescriptor(String id, String displayName, String defaultModel, String defaultBaseUrl,
                              EndpointPolicy endpointPolicy, boolean requiresKey, boolean supportsLoraId) {
        this(id, displayName, defaultModel, defaultBaseUrl, endpointPolicy, requiresKey, supportsLoraId, Map.of());
    }
    public ProviderDescriptor {
        id = normalizeId(Objects.requireNonNull(id));
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(endpointPolicy);
        modelEndpointPrefixes = Map.copyOf(modelEndpointPrefixes);
    }

    /** Mirrors a client's model-dependent fixed endpoint, using the longest matching prefix. */
    public String endpointForModel(String model) {
        String normalized = model == null || model.isBlank() ? defaultModel : model;
        normalized = normalized == null ? "" : normalized.trim().toLowerCase(Locale.ROOT);
        String selected = normalized;
        return modelEndpointPrefixes.entrySet().stream()
                .filter(entry -> selected.startsWith(entry.getKey().toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(Map.Entry::getValue).orElse(defaultBaseUrl);
    }
    public static String normalizeId(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
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
}
