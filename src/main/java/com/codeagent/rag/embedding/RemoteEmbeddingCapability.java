package com.codeagent.rag.embedding;

import java.util.Objects;

public final class RemoteEmbeddingCapability {
    private final String projectFingerprint;
    private final String providerId;
    private final String modelId;
    private final String endpointFingerprint;
    private final int consentPolicyVersion;

    RemoteEmbeddingCapability(RemoteEmbeddingConsent consent) {
        this.projectFingerprint = consent.projectFingerprint();
        this.providerId = consent.providerId();
        this.modelId = consent.modelId();
        this.endpointFingerprint = consent.endpointFingerprint();
        this.consentPolicyVersion = consent.consentPolicyVersion();
    }

    public String projectFingerprint() { return projectFingerprint; }
    public String providerId() { return providerId; }
    public String modelId() { return modelId; }
    public String endpointFingerprint() { return endpointFingerprint; }
    public int consentPolicyVersion() { return consentPolicyVersion; }

    public boolean matches(RemoteEmbeddingConsentRequest request) {
        return request != null
                && Objects.equals(projectFingerprint, request.projectFingerprint())
                && Objects.equals(providerId, request.providerId())
                && Objects.equals(modelId, request.modelId())
                && Objects.equals(endpointFingerprint, request.endpointFingerprint())
                && consentPolicyVersion == request.consentPolicyVersion();
    }
}
