package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingException;
import com.codeagent.rag.embedding.EmbeddingProvider;
import com.codeagent.rag.embedding.InProcessBgeEmbeddingProvider;
import com.codeagent.rag.embedding.OpenAiCompatibleEmbeddingProvider;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Compatibility adapter for the original single-input API. */
@Deprecated
public class EmbeddingClient implements AutoCloseable {
    private static final Set<String> REMOTE_PROVIDERS = Set.of(
            "openai", "openai-compatible", "zhipu", "glm", "jina");

    private final String providerName;
    private final String modelName;
    private final EmbeddingProvider delegate;

    public EmbeddingClient() {
        this.providerName = InProcessBgeEmbeddingProvider.PROVIDER_ID;
        this.modelName = InProcessBgeEmbeddingProvider.MODEL_ID;
        this.delegate = new InProcessBgeEmbeddingProvider();
    }

    public EmbeddingClient(String provider, String model, String baseUrl, String apiKey) {
        String normalized = requireText(provider, "provider").toLowerCase(Locale.ROOT);
        if (!REMOTE_PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported embedding provider: " + normalized);
        }
        this.providerName = normalized;
        this.modelName = requireText(model, "model");
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build();
        int dimension = ("glm".equals(normalized) || "zhipu".equals(normalized)) ? 2048 : 1536;
        this.delegate = new OpenAiCompatibleEmbeddingProvider(normalized, modelName,
                requireText(baseUrl, "baseUrl"), requireText(apiKey, "apiKey"),
                dimension, client, 3);
    }

    public float[] embed(String text) throws IOException {
        if (text == null || text.isEmpty()) return new float[0];
        try {
            return delegate.embedAll(List.of(text)).get(0);
        } catch (EmbeddingException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public String getProvider() { return providerName; }
    public String getModel() { return modelName; }

    @Override
    public void close() throws Exception {
        delegate.close();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
