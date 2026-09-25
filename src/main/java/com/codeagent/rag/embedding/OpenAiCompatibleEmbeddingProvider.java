package com.codeagent.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Native batch client for OpenAI-compatible embedding endpoints. */
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    private static final long CIRCUIT_OPEN_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final String providerId;
    private final String modelId;
    private final String endpoint;
    private final String apiKey;
    private final OkHttpClient client;
    private final int maxAttempts;
    private final EmbeddingSpaceDescriptor space;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntilNanos;

    public OpenAiCompatibleEmbeddingProvider(String providerId, String modelId, String baseUrl,
            String apiKey, int dimension, OkHttpClient client, int maxAttempts) {
        this.providerId = requireText(providerId, "providerId");
        this.modelId = requireText(modelId, "modelId");
        String normalizedBase = requireText(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.endpoint = normalizedBase + "/embeddings";
        this.apiKey = requireText(apiKey, "apiKey");
        this.client = Objects.requireNonNull(client, "client");
        if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
        this.space = EmbeddingSpaceDescriptor.create(this.providerId, this.modelId,
                sha256(URI.create(normalizedBase).normalize().toASCIIString()), "remote",
                dimension, "provider-defined", true, 1, 1);
    }

    @Override public String id() { return providerId; }
    @Override public String modelId() { return modelId; }
    @Override public EmbeddingSpaceDescriptor space() { return space; }
    @Override public EmbeddingLocality locality() { return EmbeddingLocality.REMOTE; }

    @Override
    public List<float[]> embedAll(List<String> inputs) throws EmbeddingException {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.isEmpty()) return List.of();
        if (System.nanoTime() < circuitOpenUntilNanos) {
            throw new EmbeddingException("remote_embedding_circuit_open",
                    "Remote embedding circuit is temporarily open");
        }
        try {
            List<float[]> result = executeRequest(inputs);
            consecutiveFailures.set(0);
            circuitOpenUntilNanos = 0L;
            return result;
        } catch (EmbeddingException e) {
            if (consecutiveFailures.incrementAndGet() >= CIRCUIT_FAILURE_THRESHOLD) {
                circuitOpenUntilNanos = System.nanoTime() + CIRCUIT_OPEN_NANOS;
            }
            throw e;
        }
    }

    private List<float[]> executeRequest(List<String> inputs) throws EmbeddingException {
        ObjectNode payload = MAPPER.createObjectNode().put("model", modelId);
        ArrayNode input = payload.putArray("input");
        inputs.forEach(value -> input.add(Objects.requireNonNull(value, "input")));

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Request request = new Request.Builder().url(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(payload.toString(), JSON)).build();
            try (Response response = client.newCall(request).execute()) {
                int status = response.code();
                if (!response.isSuccessful()) {
                    if (attempt < maxAttempts && retryable(status)) {
                        pause(response.header("Retry-After"));
                        continue;
                    }
                    throw new EmbeddingException("remote_embedding_http_" + status,
                            "Remote embedding request failed with HTTP " + status);
                }
                return parseResponse(response.body(), inputs.size());
            } catch (EmbeddingException e) {
                throw e;
            } catch (IOException e) {
                if (attempt == maxAttempts) {
                    throw new EmbeddingException("remote_embedding_io_failed",
                            "Remote embedding request failed", e);
                }
            }
        }
        throw new EmbeddingException("remote_embedding_failed", "Remote embedding request failed");
    }

    private List<float[]> parseResponse(ResponseBody body, int expectedCount) throws EmbeddingException {
        try {
            if (body == null) throw new IOException("missing body");
            JsonNode data = MAPPER.readTree(body.byteStream()).path("data");
            if (!data.isArray() || data.size() != expectedCount) throw new IOException("invalid data");
            List<float[]> result = new ArrayList<>(java.util.Collections.nCopies(expectedCount, null));
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                JsonNode values = item.path("embedding");
                if (index < 0 || index >= expectedCount || !values.isArray()
                        || values.size() != space.dimension() || result.get(index) != null) {
                    throw new IOException("invalid embedding");
                }
                float[] vector = new float[values.size()];
                for (int i = 0; i < vector.length; i++) vector[i] = (float) values.get(i).asDouble();
                result.set(index, vector);
            }
            if (result.stream().anyMatch(Objects::isNull)) throw new IOException("missing embedding");
            return List.copyOf(result);
        } catch (IOException e) {
            throw new EmbeddingException("remote_embedding_invalid_response",
                    "Remote embedding response was invalid", e);
        }
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static void pause(String retryAfter) throws EmbeddingException {
        if (retryAfter == null || retryAfter.isBlank()) return;
        try {
            long millis = Math.min(5_000L, Math.max(0L, Long.parseLong(retryAfter.trim()) * 1_000L));
            if (millis > 0) Thread.sleep(millis);
        } catch (NumberFormatException ignored) {
            // Ignore non-delta Retry-After values; the next bounded attempt proceeds immediately.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("remote_embedding_interrupted",
                    "Remote embedding request was interrupted", e);
        }
    }

    private static String requireText(String value, String field) {
        String text = Objects.requireNonNull(value, field).trim();
        if (text.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return text;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
