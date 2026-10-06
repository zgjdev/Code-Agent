package com.codeagent.rag;

import com.codeagent.rag.stage.TermFtsRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class TermFtsRetrieverTest {
    @Test void preservesShortClassIdentifiers(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.writeString(root.resolve("A.java"), "class A { void run() {} }");
        Files.writeString(root.resolve("Do.java"), "class Do { void act() {} }");
        Files.writeString(root.resolve("Factory.java"), "class Factory { static Factory of() { return null; } }");
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"))) {
            new IndexCoordinator(index, Optional.empty()).refresh(new IndexRefreshRequest(root, false));
            for (String name : List.of("A", "Do")) {
                assertTrue(retrieve(index, root, name).stream()
                        .anyMatch(hit -> hit.filePath().equals(name + ".java")));
            }
            assertTrue(retrieve(index, root, "of()").stream()
                    .anyMatch(hit -> hit.filePath().equals("Factory.java")));
        }
    }

    @Test void capsStrictAndSupplementedCandidatesWithUniqueChunks(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        for (int i = 0; i < 30; i++) {
            Files.writeString(root.resolve("Both" + i + ".java"),
                    "class Both" + i + " { void alphaBeta() {} }");
        }
        Files.writeString(root.resolve("Partial.java"), "class Partial { void alpha() {} }");
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"))) {
            new IndexCoordinator(index, Optional.empty()).refresh(new IndexRefreshRequest(root, false));
            var strict = retrieve(index, root, "alpha beta");
            assertEquals(20, strict.size());
            assertTrue(strict.stream().allMatch(hit -> hit.filePath().startsWith("Both")));
            var supplemented = retrieve(index, root, "alpha beta unknownword");
            assertEquals(20, supplemented.size());
            assertEquals(20, supplemented.stream().map(RetrievalCandidate::chunkId).distinct().count());
        }
    }

    @Test void retrievesChineseKeywordsWithoutRequiringTheQuestionSentence(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.writeString(root.resolve("Context.java"),
                "class Context { void compact() { /* 压缩 对话 上下文 */ } }");
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"))) {
            new IndexCoordinator(index, Optional.empty()).refresh(new IndexRefreshRequest(root, false));
            var hits = retrieve(index, root, "请问如何压缩对话上下文？");
            assertFalse(hits.isEmpty(), "sentence itself must not be a mandatory term");
            assertTrue(hits.stream().anyMatch(h -> h.content().contains("void compact()")));
        }
    }

    @Test void supplementsPartialKeywordMatchesWithoutDisplacingTheIntersection(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.writeString(root.resolve("Both.java"), "class Both { void alphaBeta() {} }");
        Files.writeString(root.resolve("Partial.java"), "class Partial { void alpha() {} }");
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"))) {
            new IndexCoordinator(index, Optional.empty()).refresh(new IndexRefreshRequest(root, false));
            var hits = retrieve(index, root, "alpha beta");
            assertFalse(hits.isEmpty());
            assertEquals("Both.java", hits.get(0).filePath());
            assertTrue(hits.stream().anyMatch(h -> h.filePath().equals("Partial.java")));
            assertEquals(hits.size(), hits.stream().map(RetrievalCandidate::chunkId).distinct().count());
        }
    }

    @Test void ignoresPureQuestionFillersAndKeepsUnknownQueriesEmpty(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.writeString(root.resolve("A.java"), "class A { void run() {} }");
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"))) {
            new IndexCoordinator(index, Optional.empty()).refresh(new IndexRefreshRequest(root, false));
            assertTrue(retrieve(index, root, "请问如何在哪里").isEmpty());
            assertTrue(retrieve(index, root, "MissingUniqueSymbol").isEmpty());
        }
    }

    private static List<RetrievalCandidate> retrieve(SqliteRetrievalIndex index, Path root, String query)
            throws Exception {
        return new TermFtsRetriever().retrieve(new RetrievalContext(
                new RetrievalRequest(root, query, 5, 4000, false, RetrievalIntent.CHUNKS), index,
                Optional.empty()));
    }
}
