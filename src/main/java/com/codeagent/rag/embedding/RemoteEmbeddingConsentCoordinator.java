package com.codeagent.rag.embedding;

import com.codeagent.policy.AuditLog;

import java.io.IOException;
import java.util.Optional;
import java.util.function.BooleanSupplier;

public final class RemoteEmbeddingConsentCoordinator {
    private static final String AUDIT_TOOL = "remote_embedding_consent";
    private static final String AUDIT_REVOKE_TOOL = "remote_embedding_consent_revoke";

    private final RemoteEmbeddingConsentStore store;
    private final AuditLog auditLog;

    public RemoteEmbeddingConsentCoordinator(RemoteEmbeddingConsentStore store, AuditLog auditLog) {
        this.store = store;
        this.auditLog = auditLog;
    }

    public Optional<RemoteEmbeddingCapability> acquire(
            RemoteEmbeddingConsentRequest request,
            BooleanSupplier approval) throws IOException {
        Optional<RemoteEmbeddingConsent> existing = store.find(request);
        if (existing.isPresent()) {
            return existing.map(RemoteEmbeddingCapability::new);
        }

        String auditArgs = auditArgs(request);
        if (!approval.getAsBoolean()) {
            auditLog.record(AuditLog.AuditEntry.denyByHitl(
                    AUDIT_TOOL, auditArgs, "remote_embedding_consent_denied", 0));
            return Optional.empty();
        }

        RemoteEmbeddingConsent consent = request.toConsent();
        store.grant(consent);
        auditLog.record(AuditLog.AuditEntry.allow(AUDIT_TOOL, auditArgs, 0));
        return Optional.of(new RemoteEmbeddingCapability(consent));
    }

    public int revoke(String projectFingerprint) throws IOException {
        int removed = store.revokeProject(projectFingerprint);
        auditLog.record(AuditLog.AuditEntry.allow(
                AUDIT_REVOKE_TOOL,
                "{\"projectFingerprint\":\"" + projectFingerprint + "\",\"removed\":" + removed + "}",
                0));
        return removed;
    }

    private static String auditArgs(RemoteEmbeddingConsentRequest request) {
        return "{\"projectFingerprint\":\"" + request.projectFingerprint()
                + "\",\"providerId\":\"" + request.providerId()
                + "\",\"modelId\":\"" + request.modelId()
                + "\",\"endpointFingerprint\":\"" + request.endpointFingerprint()
                + "\",\"consentPolicyVersion\":" + request.consentPolicyVersion() + "}";
    }
}
