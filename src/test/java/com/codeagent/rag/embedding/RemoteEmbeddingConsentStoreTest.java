package com.codeagent.rag.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteEmbeddingConsentStoreTest {

    @Test
    void persistsOnlyFingerprintsAndMatchesExactScope(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("private-project");
        Files.createDirectories(project);
        Path file = tempDir.resolve("state").resolve("remote-consents.json");
        RemoteEmbeddingConsentStore store = new RemoteEmbeddingConsentStore(file);
        RemoteEmbeddingConsentRequest request = RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-3", "https://example.test/v1?token=secret", 1);

        store.grant(request.toConsent());

        assertTrue(store.find(request).isPresent());
        String persisted = Files.readString(file);
        assertFalse(persisted.contains(project.toString()));
        assertFalse(persisted.contains("example.test"));
        assertFalse(persisted.contains("secret"));
        assertTrue(persisted.contains(request.projectFingerprint()));
    }

    @Test
    void providerModelEndpointAndPolicyChangesInvalidateConsent(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        RemoteEmbeddingConsentStore store = new RemoteEmbeddingConsentStore(
                tempDir.resolve("state").resolve("remote-consents.json"));
        RemoteEmbeddingConsentRequest original = RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-3", "https://example.test/v1", 1);
        store.grant(original.toConsent());

        assertFalse(store.find(RemoteEmbeddingConsentRequest.create(
                project, "jina", "embedding-3", "https://example.test/v1", 1)).isPresent());
        assertFalse(store.find(RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-4", "https://example.test/v1", 1)).isPresent());
        assertFalse(store.find(RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-3", "https://other.test/v1", 1)).isPresent());
        assertFalse(store.find(RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-3", "https://example.test/v1", 2)).isPresent());
    }

    @Test
    void revokesAllConsentForProject(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        RemoteEmbeddingConsentStore store = new RemoteEmbeddingConsentStore(
                tempDir.resolve("state").resolve("remote-consents.json"));
        RemoteEmbeddingConsentRequest glm = RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-3", "https://glm.test/v1", 1);
        RemoteEmbeddingConsentRequest jina = RemoteEmbeddingConsentRequest.create(
                project, "jina", "jina-code", "https://jina.test/v1", 1);
        store.grant(glm.toConsent());
        store.grant(jina.toConsent());

        assertEquals(2, store.revokeProject(glm.projectFingerprint()));
        assertTrue(store.find(glm).isEmpty());
        assertTrue(store.find(jina).isEmpty());
    }

    @Test
    void restrictsPermissionsWhenPosixIsAvailable(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("state").resolve("remote-consents.json");
        FileStore fileStore = Files.getFileStore(tempDir);
        if (!fileStore.supportsFileAttributeView("posix")) {
            return;
        }
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        RemoteEmbeddingConsentStore store = new RemoteEmbeddingConsentStore(file);
        store.grant(RemoteEmbeddingConsentRequest.create(
                project, "glm", "embedding-3", "https://example.test/v1", 1).toConsent());

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(file.getParent()));
    }
}
