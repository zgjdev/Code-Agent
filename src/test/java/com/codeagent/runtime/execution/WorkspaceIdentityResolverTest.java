package com.codeagent.runtime.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceIdentityResolverTest {
    private final WorkspaceIdentityResolver resolver = new WorkspaceIdentityResolver();

    @Test
    void resolvesCanonicalIdentityForEquivalentPaths(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.createDirectory(tempDir.resolve("nested"));
        WorkspaceIdentity direct = resolver.resolve(workspace);
        WorkspaceIdentity alias = resolver.resolve(tempDir.resolve("nested").resolve("..").resolve("workspace"));

        assertEquals(workspace.toRealPath(), direct.canonicalRoot());
        assertEquals(direct, alias);
        assertTrue(direct.key().value().startsWith("ws_"));
    }

    @Test
    void rejectsMissingAndNonDirectoryRoots(@TempDir Path tempDir) throws Exception {
        assertThrows(java.io.IOException.class, () -> resolver.resolve(tempDir.resolve("missing")));
        Path file = Files.createFile(tempDir.resolve("file"));
        assertThrows(java.io.IOException.class, () -> resolver.resolve(file));
    }
}
