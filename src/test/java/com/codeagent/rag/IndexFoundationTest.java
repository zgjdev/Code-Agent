package com.codeagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexFoundationTest {

    @Test
    void snapshotInputDoesNotRereadChangedFile(@TempDir Path root) throws Exception {
        Path file = root.resolve("A.java");
        Files.writeString(file, "class A extends Old {}");
        var snapshot = FileContentSnapshot.read(new IndexPathPolicy(root), file);
        Files.writeString(file, "class B extends New {}");
        assertTrue(new CodeChunker().chunkContent(file, snapshot.content()).stream().anyMatch(c -> c.name().equals("A")));
        assertTrue(new CodeAnalyzer().analyzeContent(file, snapshot.content()).stream().anyMatch(r -> r.toName().equals("Old")));
    }

    @Test
    void sharedPolicyRejectsIgnoredParentsAndSecrets(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        Files.createDirectories(root.resolve("ignored"));
        Path ignored = root.resolve("ignored/A.java");
        Files.writeString(ignored, "class A {}");
        Files.writeString(root.resolve("credentials.json"), "{}");
        var policy = new IndexPathPolicy(root);
        assertFalse(policy.isEligible(ignored));
        assertFalse(policy.isEligible(root.resolve("credentials.json")));
    }

    @Test
    void lexicalReconcileAndMetadataTasksAreSeparate(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("A.java"), "class A {}");
        try (var index = new SqliteRetrievalIndex(root.resolve("index.db"))) {
            var coordinator = new IndexCoordinator(index, Optional.empty());
            assertEquals(1, coordinator.reconcileLexical(new IndexRefreshRequest(root, false)).changedFiles());
            assertEquals(1, index.listFiles(root).size());
        }
    }

    @Test
    void policyRejectsRootLinksAndFileLinks(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("A.java"), "class A {}");
        try {
            Files.createSymbolicLink(root.resolve("A.java"), outside.resolve("A.java"));
            Files.createSymbolicLink(temp.resolve("root-link"), root);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links unavailable");
        }
        assertFalse(new IndexPathPolicy(root).isEligible(root.resolve("A.java")));
        assertThrows(java.io.IOException.class, () -> new IndexPathPolicy(temp.resolve("root-link")));
    }

    @Test
    void singlePathRefreshDeletesConfirmedMissingFile(@TempDir Path root) throws Exception {
        Path file = root.resolve("A.java");
        Files.writeString(file, "class A {}");
        try (var index = new SqliteRetrievalIndex(root.resolve("index.db"))) {
            var coordinator = new IndexCoordinator(index, Optional.empty());
            assertEquals(1, coordinator.refreshLexicalPath(root, file).changedFiles());
            Files.delete(file);
            assertEquals(1, coordinator.refreshLexicalPath(root, file).deletedFiles());
            assertTrue(index.listFiles(root).isEmpty());
        }
    }
    @Test
    void rebuildFailurePreservesPreviouslyCommittedFile(@TempDir Path root) throws Exception {
        Path file = root.resolve("A.java");
        Files.writeString(file, "class A {}");
        try (var index = new SqliteRetrievalIndex(root.resolve("index.db"))) {
            new IndexCoordinator(index, Optional.empty()).refresh(new IndexRefreshRequest(root, false));
            String oldHash = index.findFile(root, Path.of("A.java")).orElseThrow().contentHash();
            CodeChunker failing = new CodeChunker() {
                @Override public List<CodeChunk> chunkContent(Path path, String content) {
                    throw new IllegalStateException("injected parser failure");
                }
            };
            var coordinator = new IndexCoordinator(index, Optional.empty(), new IndexFileScanner(), failing,
                    new CodeAnalyzer(), new LexicalTextNormalizer(), new com.codeagent.rag.embedding.EmbeddingInputPolicy());
            assertEquals(1, coordinator.rebuild(new IndexRefreshRequest(root, true)).failedFiles());
            assertEquals(oldHash, index.findFile(root, Path.of("A.java")).orElseThrow().contentHash());
            assertFalse(index.searchTerms(root, "A", 10).isEmpty());
        }
    }

    @Test
    void restrictedReaderRechecksIdentityBeforeConsumingBytes(@TempDir Path root) throws Exception {
        Path file = root.resolve("A.java"); Files.writeString(file, "class A {}");
        assertThrows(java.io.IOException.class, () -> IndexRestrictedReader.read(root, file, () -> {
            try { Files.delete(file); Files.writeString(file, "class B {}"); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }));
    }
    @Test
    void closedCommitGuardPreventsLexicalWrites(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("A.java"),"class A {}");
        try(var index=new SqliteRetrievalIndex(root.resolve("index.db"))) {
            var coordinator=new IndexCoordinator(index,Optional.empty()).withCommitGuard(new Object(),()->false);
            assertTrue(coordinator.reconcileLexical(new IndexRefreshRequest(root,false)).reasonCodes().contains("maintenance_closed"));
            assertTrue(index.listFiles(root).isEmpty());
        }
    }
}
