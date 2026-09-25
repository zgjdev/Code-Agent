package com.codeagent.rag.embedding;

import java.time.Instant;
import java.util.Objects;

public record RemoteEmbeddingConsent(
        String projectFingerprint,
        String providerId,
        String modelId,
        String endpointFingerprint,
        int consentPolicyVersion,
        Instant grantedAt
) {
    public RemoteEmbeddingConsent {
        Objects.requireNonNull(projectFingerprint, "projectFingerprint");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(endpointFingerprint, "endpointFingerprint");
        Objects.requireNonNull(grantedAt, "grantedAt");
        if (consentPolicyVersion <= 0) {
            throw new IllegalArgumentException("consentPolicyVersion must be positive");
        }
    }
}
