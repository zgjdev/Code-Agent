package com.codeagent.rag.embedding;

import com.codeagent.policy.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteEmbeddingConsentCoordinatorTest {

    @Test
    void approvalPersistsConsentAndReturnsScopedCapability(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        RemoteEmbeddingConsentStore store = store(tempDir);
        RemoteEmbeddingConsentCoordinator coordinator = coordinator(tempDir, store);
        RemoteEmbeddingConsentRequest request = RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-3", "https://example.test/v1", 1);

        Optional<RemoteEmbeddingCapability> capability = coordinator.acquire(request, () -> true);

        assertTrue(capability.isPresent());
        assertTrue(capability.orElseThrow().matches(request));
        assertTrue(store.find(request).isPresent());
    }

    @Test
    void cachedConsentDoesNotPromptAgain(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        RemoteEmbeddingConsentStore store = store(tempDir);
        RemoteEmbeddingConsentCoordinator coordinator = coordinator(tempDir, store);
        RemoteEmbeddingConsentRequest request = RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-3", "https://example.test/v1", 1);
        store.grant(request.toConsent());
        AtomicInteger prompts = new AtomicInteger();

        Optional<RemoteEmbeddingCapability> capability = coordinator.acquire(request, () -> {
            prompts.incrementAndGet();
            return false;
        });

        assertTrue(capability.isPresent());
        assertEquals(0, prompts.get());
    }

    @Test
    void denialIsAuditedButNotPersisted(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("secret-project");
        Files.createDirectories(project);
        RemoteEmbeddingConsentStore store = store(tempDir);
        AuditLog auditLog = new AuditLog(tempDir.resolve("audit"));
        RemoteEmbeddingConsentCoordinator coordinator =
                new RemoteEmbeddingConsentCoordinator(store, auditLog);
        RemoteEmbeddingConsentRequest request = RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-3", "https://user:password@example.test/v1?apiKey=secret", 1);

        Optional<RemoteEmbeddingCapability> capability = coordinator.acquire(request, () -> false);

        assertTrue(capability.isEmpty());
        assertTrue(store.find(request).isEmpty());
        AuditLog.AuditEntry entry = auditLog.readRecent(1).get(0);
        assertEquals("remote_embedding_consent", entry.tool());
        assertEquals(AuditLog.OUTCOME_DENY, entry.outcome());
        assertFalse(entry.args().contains(project.toString()));
        assertFalse(entry.args().contains("example.test"));
        assertFalse(entry.args().contains("secret"));
    }

    @Test
    void changedScopeRequiresNewDecision(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        RemoteEmbeddingConsentStore store = store(tempDir);
        RemoteEmbeddingConsentCoordinator coordinator = coordinator(tempDir, store);
        RemoteEmbeddingConsentRequest original = RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-3", "https://example.test/v1", 1);
        store.grant(original.toConsent());
        AtomicInteger prompts = new AtomicInteger();
        RemoteEmbeddingConsentRequest changed = RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-4", "https://example.test/v1", 1);

        Optional<RemoteEmbeddingCapability> capability = coordinator.acquire(changed, () -> {
            prompts.incrementAndGet();
            return false;
        });

        assertTrue(capability.isEmpty());
        assertEquals(1, prompts.get());
    }

    @Test
    void revokeRemovesConsentAndWritesAuditEntry(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        RemoteEmbeddingConsentStore store = store(tempDir);
        AuditLog auditLog = new AuditLog(tempDir.resolve("audit"));
        RemoteEmbeddingConsentCoordinator coordinator =
                new RemoteEmbeddingConsentCoordinator(store, auditLog);
        RemoteEmbeddingConsentRequest request = RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-3", "https://example.test/v1", 1);
        store.grant(request.toConsent());

        assertEquals(1, coordinator.revoke(request.projectFingerprint()));

        assertTrue(store.find(request).isEmpty());
        AuditLog.AuditEntry entry = auditLog.readRecent(1).get(0);
        assertEquals("remote_embedding_consent_revoke", entry.tool());
        assertEquals(AuditLog.OUTCOME_ALLOW, entry.outcome());
    }

    private static RemoteEmbeddingConsentStore store(Path tempDir) {
        return new RemoteEmbeddingConsentStore(tempDir.resolve("state").resolve("remote-consents.json"));
    }

    private static RemoteEmbeddingConsentCoordinator coordinator(
            Path tempDir, RemoteEmbeddingConsentStore store) {
        return new RemoteEmbeddingConsentCoordinator(store, new AuditLog(tempDir.resolve("audit")));
    }
}
