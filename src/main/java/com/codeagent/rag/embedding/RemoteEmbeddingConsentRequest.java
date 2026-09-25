package com.codeagent.rag.embedding;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public record RemoteEmbeddingConsentRequest(
        String projectFingerprint,
        String providerId,
        String modelId,
        String endpointFingerprint,
        int consentPolicyVersion
) {
    public RemoteEmbeddingConsentRequest {
        projectFingerprint = requireText(projectFingerprint, "projectFingerprint");
        providerId = requireText(providerId, "providerId");
        modelId = requireText(modelId, "modelId");
        endpointFingerprint = requireText(endpointFingerprint, "endpointFingerprint");
        if (consentPolicyVersion <= 0) {
            throw new IllegalArgumentException("consentPolicyVersion must be positive");
        }
    }

    public static RemoteEmbeddingConsentRequest create(
            Path projectRoot,
            String providerId,
            String modelId,
            String endpoint,
            int consentPolicyVersion) throws IOException {
        Path realRoot = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        return new RemoteEmbeddingConsentRequest(
                sha256(realRoot.toUri().normalize().toString()),
                providerId,
                modelId,
                sha256(normalizeEndpoint(endpoint)),
                consentPolicyVersion);
    }

    public RemoteEmbeddingConsent toConsent() {
        return new RemoteEmbeddingConsent(projectFingerprint, providerId, modelId,
                endpointFingerprint, consentPolicyVersion, Instant.now());
    }

    private static String normalizeEndpoint(String endpoint) {
        String value = requireText(endpoint, "endpoint");
        try {
            URI uri = new URI(value).normalize();
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            if (path != null && path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return new URI(scheme, uri.getUserInfo(), host, uri.getPort(), path,
                    uri.getQuery(), null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid embedding endpoint", e);
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

    private static String requireText(String value, String field) {
        String text = Objects.requireNonNull(value, field).trim();
        if (text.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return text;
    }
}
