package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingSpaceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteRetrievalIndexTest {

    @Test
    void createsV2SchemaAndSingleSchemaRow(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("codebase-v2.db");

        try (SqliteRetrievalIndex ignored = new SqliteRetrievalIndex(database)) {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                assertEquals(1, scalarInt(statement, "SELECT COUNT(*) FROM rag_schema"));
                assertEquals(2, scalarInt(statement, "SELECT version FROM rag_schema"));
                assertEquals(1, scalarInt(statement,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='code_chunks_terms_fts_v2'"));
                assertEquals(1, scalarInt(statement,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='code_chunks_trigram_fts_v2'"));
                assertEquals("ok", statement.executeQuery("PRAGMA integrity_check").getString(1));
            }
        }
    }

    @Test
    void lexicalReplacementMaintainsBothFtsIndexes(@TempDir Path tempDir) throws Exception {
        Path project = project(tempDir);
        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(tempDir.resolve("codebase-v2.db"))) {
            index.replaceLexicalFile(batch(project, "src/FooService.java", "hash-1",
                    "// 上下文压缩\nclass FooService { void compactContext() {} }",
                    "FooService", "compact context 上下文 压缩"));

            assertEquals("src/FooService.java",
                    index.searchTerms(project, "compact context", 5).get(0).filePath());
            assertEquals("src/FooService.java",
                    index.searchTrigram(project, "上下文压缩", 5).get(0).filePath());
            assertEquals("FooService", index.searchSymbols(project, "foo", 5).get(0).symbol());

            index.replaceLexicalFile(batch(project, "src/FooService.java", "hash-2",
                    "class RenamedService {}", "RenamedService", "renamed service 重命名"));

            assertTrue(index.searchTerms(project, "compact", 5).isEmpty());
            assertTrue(index.searchTrigram(project, "上下文", 5).isEmpty());
            assertEquals("RenamedService", index.searchSymbols(project, "renamed", 5).get(0).symbol());
        }
    }

    @Test
    void safelyHandlesFtsOperatorsQuotesAndShortTrigramQueries(@TempDir Path tempDir) throws Exception {
        Path project = project(tempDir);
        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(tempDir.resolve("codebase-v2.db"))) {
            index.replaceLexicalFile(batch(project, "src/A.java", "hash", "class A {}", "A", "and or not quote"));

            index.searchTerms(project, "AND OR NOT \" ( )", 5);
            index.searchTrigram(project, "A", 5);
        }
    }

    @Test
    void storesLittleEndianFloatBlobsAndSkipsCorruptRows(@TempDir Path tempDir) throws Exception {
        Path project = project(tempDir);
        Path database = tempDir.resolve("codebase-v2.db");
        EmbeddingSpaceDescriptor space = space();
        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(database)) {
            FileIndexBatch batch = batch(project, "src/A.java", "hash", "class A {}", "A", "class a");
            index.replaceLexicalFile(batch);
            index.replaceFileEmbeddings(new FileEmbeddingBatch(project, Path.of("src/A.java"), space,
                    List.of(new ChunkEmbedding(1, 1, batch.chunks().get(0).symbolId(), "hash", new float[]{1f, 0f, 0f}))));

            List<RetrievalCandidate> hits = index.searchVector(project, space.embeddingSpaceId(),
                    new float[]{1f, 0f, 0f}, 5);
            assertEquals(1, hits.size());
            assertTrue(hits.get(0).score() > 0.99);

            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE chunk_embeddings_v2 SET vector_blob=x'0001'");
            }
            assertTrue(index.searchVector(project, space.embeddingSpaceId(),
                    new float[]{1f, 0f, 0f}, 5).isEmpty());
        }
    }

    @Test
    void skipsEmbeddingWhoseSourceHashNoLongerMatchesChunk(@TempDir Path tempDir) throws Exception {
        Path project = project(tempDir);
        EmbeddingSpaceDescriptor space = space();
        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(tempDir.resolve("codebase-v2.db"))) {
            FileIndexBatch batch = batch(project, "src/A.java", "current", "class A {}", "A", "class a");
            index.replaceLexicalFile(batch);
            index.replaceFileEmbeddings(new FileEmbeddingBatch(project, Path.of("src/A.java"), space,
                    List.of(new ChunkEmbedding(1, 1, batch.chunks().get(0).symbolId(), "stale", new float[]{1f, 0f, 0f}))));

            assertTrue(index.searchVector(project, space.embeddingSpaceId(),
                    new float[]{1f, 0f, 0f}, 5).isEmpty());
        }
    }

    @Test
    void stableSymbolIdsDistinguishOwnersAndOverloadsButIgnoreLineMoves() {
        String first = StableSymbolId.create("src/A.java", "METHOD", "p.A.run", "run(String)");
        String moved = StableSymbolId.create("src/A.java", "METHOD", "p.A.run", "run(String)");
        String overload = StableSymbolId.create("src/A.java", "METHOD", "p.A.run", "run(int)");
        String nestedOwner = StableSymbolId.create("src/A.java", "METHOD", "p.A.Inner.run", "run(String)");

        assertEquals(first, moved);
        assertNotEquals(first, overload);
        assertNotEquals(first, nestedOwner);
    }

    @Test
    void failedReplacementRollsBackToPreviousCompleteFile(@TempDir Path tempDir) throws Exception {
        Path project = project(tempDir);
        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(tempDir.resolve("codebase-v2.db"))) {
            FileIndexBatch original = batch(project, "src/A.java", "old", "class A {}", "A", "original token");
            index.replaceLexicalFile(original);
            IndexedSymbol duplicate = original.symbols().get(0);
            FileIndexBatch invalid = new FileIndexBatch(project, Path.of("src/A.java"), "new", 20, 2,
                    "java", "READY", null, original.chunks(), List.of(duplicate, duplicate), List.of());

            assertThrows(Exception.class, () -> index.replaceLexicalFile(invalid));
            assertTrue(index.searchTerms(project, "original", 5).size() == 1);
            assertEquals("old", index.findFile(project, Path.of("src/A.java")).orElseThrow().contentHash());
        }
    }

    @Test
    void deletingFileCascadesChunksFtsSymbolsRelationsAndVectors(@TempDir Path tempDir) throws Exception {
        Path project = project(tempDir);
        Path database = tempDir.resolve("codebase-v2.db");
        EmbeddingSpaceDescriptor space = space();
        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(database)) {
            FileIndexBatch batch = batch(project, "src/A.java", "hash", "class A {}", "A", "cascade token");
            index.replaceLexicalFile(batch);
            index.replaceFileEmbeddings(new FileEmbeddingBatch(project, Path.of("src/A.java"), space,
                    List.of(new ChunkEmbedding(1, 1, batch.chunks().get(0).symbolId(), "hash", new float[]{1f, 0f, 0f}))));

            index.deleteFile(project, Path.of("src/A.java"));

            assertTrue(index.searchTerms(project, "cascade", 5).isEmpty());
            assertTrue(index.searchSymbols(project, "A", 5).isEmpty());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                assertEquals(0, scalarInt(statement, "SELECT COUNT(*) FROM code_chunks_v2"));
                assertEquals(0, scalarInt(statement, "SELECT COUNT(*) FROM code_chunks_terms_fts_v2"));
                assertEquals(0, scalarInt(statement, "SELECT COUNT(*) FROM code_chunks_trigram_fts_v2"));
                assertEquals(0, scalarInt(statement, "SELECT COUNT(*) FROM code_symbols_v2"));
                assertEquals(0, scalarInt(statement, "SELECT COUNT(*) FROM code_relations_v2"));
                assertEquals(0, scalarInt(statement, "SELECT COUNT(*) FROM chunk_embeddings_v2"));
            }
        }
    }

    @Test
    void returnsResolvedAndUnresolvedRelations(@TempDir Path tempDir) throws Exception {
        Path project = project(tempDir);
        String fileId = StableSymbolId.create("src/A.java", "FILE", "src/A.java", "");
        String classId = StableSymbolId.create("src/A.java", "CLASS", "p.A", "");
        FileIndexBatch batch = new FileIndexBatch(project, Path.of("src/A.java"), "hash", 10, 1,
                "java", "READY", null,
                List.of(new IndexedChunk(1, 1, "class", "A", classId, "class A {}", "class a", "hash")),
                List.of(
                        new IndexedSymbol(fileId, "src/A.java", "src/A.java", "", "FILE", null, 1, 1),
                        new IndexedSymbol(classId, "p.A", "A", "", "CLASS", fileId, 1, 1)),
                List.of(
                        new IndexedRelation(fileId, classId, "A", "contains", 1),
                        new IndexedRelation(classId, null, "Missing", "calls", 1)));
        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(tempDir.resolve("codebase-v2.db"))) {
            index.replaceLexicalFile(batch);

            List<RetrievalCandidate> relations = index.searchRelations(project, Set.of(classId), 10);
            assertEquals(2, relations.size());
            assertTrue(relations.stream().anyMatch(candidate -> candidate.resolvedRelation()));
            assertTrue(relations.stream().anyMatch(candidate -> !candidate.resolvedRelation()));
        }
    }

    @Test
    void reportsLegacyDatabaseWithoutMutatingIt(@TempDir Path tempDir) throws Exception {
        Path project = project(tempDir);
        Path legacy = tempDir.resolve("codebase.db");
        byte[] original = "legacy-bytes".getBytes();
        Files.write(legacy, original);

        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(tempDir.resolve("codebase-v2.db"), legacy)) {
            assertTrue(index.status(project).legacyDatabasePresent());
        }
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(legacy)));
    }

    @Test
    void fileChunksUseRealOneBasedLines() {
        CodeChunk chunk = CodeChunk.fileChunk("README.md", "one\ntwo\nthree");

        assertEquals(1, chunk.startLine());
        assertEquals(3, chunk.endLine());
    }

    private static FileIndexBatch batch(Path project, String file, String hash,
                                        String content, String symbol, String terms) {
        String fileId = StableSymbolId.create(file, "FILE", file, "");
        String classId = StableSymbolId.create(file, "CLASS", "p." + symbol, "");
        return new FileIndexBatch(project, Path.of(file), hash, content.length(), 1,
                "java", "READY", null,
                List.of(new IndexedChunk(1, Math.max(1, content.lines().toList().size()), "class",
                        symbol, classId, content, terms, hash)),
                List.of(
                        new IndexedSymbol(fileId, file, Path.of(file).getFileName().toString(), "",
                                "FILE", null, 1, Math.max(1, content.lines().toList().size())),
                        new IndexedSymbol(classId, "p." + symbol, symbol, "", "CLASS", fileId,
                                1, Math.max(1, content.lines().toList().size()))),
                List.of(new IndexedRelation(fileId, classId, symbol, "contains", 1)));
    }

    private static EmbeddingSpaceDescriptor space() {
        return EmbeddingSpaceDescriptor.create("test", "model", "endpoint", "artifact",
                3, "mean", true, 1, 1);
    }

    private static Path project(Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        return project.toRealPath();
    }

    private static int scalarInt(java.sql.Statement statement, String sql) throws Exception {
        try (var result = statement.executeQuery(sql)) {
            return result.getInt(1);
        }
    }
}
