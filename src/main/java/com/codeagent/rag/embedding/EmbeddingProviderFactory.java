package com.codeagent.rag.embedding;

import com.codeagent.config.CodeAgentConfig;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class EmbeddingProviderFactory {
    private static final int CONSENT_POLICY_VERSION = 1;

    public EmbeddingResolution resolve(CodeAgentConfig config, Path projectRoot,
            RemoteEmbeddingCapability capability) throws IOException {
        CodeAgentConfig.EmbeddingConfig embedding = config.getEmbedding();
        String mode = embedding.getMode().toLowerCase(Locale.ROOT);
        if ("off".equals(mode)) return unavailable("embedding_disabled", false);
        if ("local".equals(mode) || "auto".equals(mode)) return local("local_embedding");
        if (!"remote".equals(mode)) return local("unsupported_embedding_provider");

        RemoteSettings settings = remoteSettings(embedding);
        if (settings == null) return unavailable("unsupported_embedding_provider", false);
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            return unavailable("remote_embedding_api_key_missing", false);
        }
        RemoteEmbeddingConsentRequest request = RemoteEmbeddingConsentRequest.create(projectRoot,
                settings.provider(), settings.model(), settings.baseUrl(), CONSENT_POLICY_VERSION);
        if (capability == null || !capability.matches(request)) {
            return unavailable("remote_embedding_consent_required", true);
        }
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build();
        return new EmbeddingResolution(Optional.of(new OpenAiCompatibleEmbeddingProvider(
                settings.provider(), settings.model(), settings.baseUrl(), settings.apiKey(),
                settings.dimension(), client, 3)), "remote_embedding", false);
    }

    private static EmbeddingResolution local(String reason) {
        return new EmbeddingResolution(Optional.of(new InProcessBgeEmbeddingProvider()), reason, false);
    }

    private static EmbeddingResolution unavailable(String reason, boolean consentRequired) {
        return new EmbeddingResolution(Optional.empty(), reason, consentRequired);
    }

    private static RemoteSettings remoteSettings(CodeAgentConfig.EmbeddingConfig config) {
        String provider = config.getProvider();
        if (provider == null) return null;
        String normalized = provider.toLowerCase(Locale.ROOT);
        String model;
        String baseUrl;
        int dimension;
        switch (normalized) {
            case "glm", "zhipu" -> {
                model = valueOr(config.getModel(), "embedding-3");
                baseUrl = valueOr(config.getBaseUrl(), "https://open.bigmodel.cn/api/paas/v4");
                dimension = config.getDimension() > 0 ? config.getDimension() : 2048;
            }
            case "jina" -> {
                model = valueOr(config.getModel(), "jina-code-embeddings-1.5b");
                baseUrl = valueOr(config.getBaseUrl(), "https://api.jina.ai/v1");
                dimension = config.getDimension() > 0 ? config.getDimension() : 1536;
            }
            case "openai-compatible" -> {
                if (config.getModel() == null || config.getBaseUrl() == null
                        || config.getDimension() <= 0) return null;
                model = config.getModel();
                baseUrl = config.getBaseUrl();
                dimension = config.getDimension();
            }
            default -> { return null; }
        }
        return new RemoteSettings(normalized, model, baseUrl, config.getApiKey(), dimension);
    }

    private static String valueOr(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private record RemoteSettings(String provider, String model, String baseUrl,
                                  String apiKey, int dimension) {}
}
