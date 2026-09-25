package com.codeagent.rag.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

public record EmbeddingSpaceDescriptor(
        String embeddingSpaceId,
        String providerId,
        String modelId,
        String endpointFingerprint,
        String artifactRevision,
        int dimension,
        String pooling,
        boolean normalized,
        int preprocessingVersion,
        int chunkerVersion
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public EmbeddingSpaceDescriptor {
        Objects.requireNonNull(embeddingSpaceId, "embeddingSpaceId");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(endpointFingerprint, "endpointFingerprint");
        Objects.requireNonNull(artifactRevision, "artifactRevision");
        Objects.requireNonNull(pooling, "pooling");
        if (dimension <= 0) throw new IllegalArgumentException("dimension must be positive");
        String expectedId = computeSpaceId(providerId, modelId, endpointFingerprint,
                artifactRevision, dimension, pooling, normalized,
                preprocessingVersion, chunkerVersion);
        if (!expectedId.equals(embeddingSpaceId)) {
            throw new IllegalArgumentException("embeddingSpaceId does not match descriptor fields");
        }
    }

    public static EmbeddingSpaceDescriptor create(
            String providerId,
            String modelId,
            String endpointFingerprint,
            String artifactRevision,
            int dimension,
            String pooling,
            boolean normalized,
            int preprocessingVersion,
            int chunkerVersion) {
        return new EmbeddingSpaceDescriptor(
                computeSpaceId(providerId, modelId, endpointFingerprint, artifactRevision,
                        dimension, pooling, normalized, preprocessingVersion, chunkerVersion),
                providerId, modelId, endpointFingerprint, artifactRevision, dimension, pooling,
                normalized, preprocessingVersion, chunkerVersion);
    }

    private static String computeSpaceId(
            String providerId,
            String modelId,
            String endpointFingerprint,
            String artifactRevision,
            int dimension,
            String pooling,
            boolean normalized,
            int preprocessingVersion,
            int chunkerVersion) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("providerId", providerId);
        canonical.put("modelId", modelId);
        canonical.put("endpointFingerprint", endpointFingerprint);
        canonical.put("artifactRevision", artifactRevision);
        canonical.put("dimension", dimension);
        canonical.put("pooling", pooling);
        canonical.put("normalized", normalized);
        canonical.put("preprocessingVersion", preprocessingVersion);
        canonical.put("chunkerVersion", chunkerVersion);
        try {
            String json = MAPPER.writeValueAsString(canonical);
            return sha256(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize embedding space descriptor", e);
        }
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
