package com.codeagent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRetrievalPersistenceTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");

    private MemoryEntry fact(String id, String content) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.FACT, NOW,
                Map.of("scope", "global"), 10);
    }

    private MemoryRetriever retriever(LongTermMemory memory, MemoryTestEmbeddingProvider provider) {
        return new MemoryRetriever(memory, new MemoryEmbeddingCache(provider), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void lexicalSearchMatchesTokensRatherThanEnglishSubstrings() {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        memory.store(fact("js", "JavaScript browser frontend"));
        try (MemoryRetriever search = retriever(memory, new MemoryTestEmbeddingProvider().fail(true))) {
            assertTrue(search.retrieve("Java", 5).isEmpty(), "Java must not match the JavaScript token");
        }
    }

    @Test
    void lexicalSearchUsesBm25RareTermsRatherThanEqualKeywordCoverage() {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        memory.store(fact("a-common", "Java common"));
        memory.store(fact("z-rare", "Redis cache"));
        for (int i = 0; i < 8; i++) memory.store(fact("filler-" + i, "Java framework " + i));
        try (MemoryRetriever search = retriever(memory, new MemoryTestEmbeddingProvider().fail(true))) {
            assertEquals("z-rare", search.retrieve("Java Redis", 10).get(0).getId());
        }
    }

    @Test
    void reopeningMemoryReusesPersistedEntryVectorAndStillEmbedsQuery() {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        String text = "Redis cache configuration";
        memory.store(fact("redis", text));
        MemoryTestEmbeddingProvider first = new MemoryTestEmbeddingProvider()
                .vector(text, 1, 0).vector("Redis", 1, 0);
        try (MemoryRetriever search = retriever(memory, first)) {
            assertEquals(1, search.retrieve("Redis", 5).size());
        }
        MemoryTestEmbeddingProvider second = new MemoryTestEmbeddingProvider()
                .vector(text, 1, 0).vector("Redis", 1, 0);
        try (MemoryRetriever search = retriever(new LongTermMemory(directory.toFile()), second)) {
            assertEquals(1, search.retrieve("Redis", 5).size());
        }
        assertEquals(0, second.calls(text), "A fresh cache must reuse the SQLite vector");
        assertEquals(1, second.calls("Redis"));
    }

    @Test
    void confirmationDoesNotInvalidateVectorButContentAndSpaceChangesDo() {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        String initial = "Redis original cache";
        String updated = "Redis revised cache";
        memory.store(fact("redis", initial));
        try (MemoryRetriever search = retriever(memory, new MemoryTestEmbeddingProvider())) {
            search.retrieve("Redis", 5);
        }
        memory.confirm("redis", NOW.plusSeconds(60));
        MemoryTestEmbeddingProvider confirmed = new MemoryTestEmbeddingProvider();
        try (MemoryRetriever search = retriever(memory, confirmed)) { search.retrieve("Redis", 5); }
        assertEquals(0, confirmed.calls(initial));
        memory.store(fact("redis", updated));
        MemoryTestEmbeddingProvider changed = new MemoryTestEmbeddingProvider();
        try (MemoryRetriever search = retriever(memory, changed)) { search.retrieve("Redis", 5); }
        assertEquals(1, changed.calls(updated));
        MemoryTestEmbeddingProvider otherSpace = new MemoryTestEmbeddingProvider(3);
        try (MemoryRetriever search = retriever(memory, otherSpace)) { search.retrieve("Redis", 5); }
        assertEquals(1, otherSpace.calls(updated));
    }

    @Test
    void jointlyMatchedMemoryWinsRrfEvenWhenSingleChannelRawScoreIsHigher() {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        String query = "Redis backend options settings connection";
        String both = "Redis cache configuration";
        String semanticOnly = "Volatile database preferences";
        memory.store(fact("both", both));
        memory.store(fact("semantic", semanticOnly));
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector(query, 1, 0)
                .vector(both, 0.5f, (float) Math.sqrt(0.75))
                .vector(semanticOnly, 1, 0);
        try (MemoryRetriever search = retriever(memory, provider)) {
            assertEquals(List.of("both", "semantic"), search.retrieve(query, 5).stream()
                    .map(MemoryEntry::getId).toList());
        }
    }

    @Test
    void contextCanBackfillFromAfterFirstTenCandidates() {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        for (int i = 0; i < 10; i++) {
            memory.store(new MemoryEntry("large-" + i, "Redis large configuration " + i,
                    MemoryEntry.MemoryType.FACT, NOW, Map.of("scope", "global"), 1000));
        }
        memory.store(new MemoryEntry("small", "Redis concise setting", MemoryEntry.MemoryType.FACT,
                NOW.minusSeconds(3650L * 86400), Map.of("scope", "global"), 5));
        try (MemoryRetriever search = retriever(memory, new MemoryTestEmbeddingProvider().fail(true))) {
            assertTrue(search.buildContextForQuery("Redis", 10).contains("concise"));
        }
    }

    @Test
    void retrievalSchemaExistsAndDerivedVectorsAreDeletedWithFacts() throws Exception {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        memory.store(fact("redis", "Redis cache"));
        try (MemoryRetriever search = retriever(memory, new MemoryTestEmbeddingProvider())) {
            search.retrieve("Redis", 5);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("memory.db"));
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE name='long_term_memory_embeddings'")) {
                assertTrue(rows.next(), "Persistent vector table must be initialized");
            }
            assertTrue(memory.delete("redis"));
            try (var rows = statement.executeQuery("SELECT count(*) FROM long_term_memory_embeddings")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void oldFactsDatabaseIsBackfilledWithoutEmbeddingInference() throws Exception {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        memory.store(fact("chinese", "默认使用中文回答用户"));
        execute("DROP TRIGGER memory_retrieval_delete", "DROP TRIGGER memory_terms_invalidate",
                "DROP TRIGGER memory_embedding_invalidate", "DROP TABLE long_term_memory_terms_fts",
                "DROP TABLE long_term_memory_embeddings");
        LongTermMemory upgraded = new LongTermMemory(directory.toFile());
        try (MemoryRetriever search = retriever(upgraded, new MemoryTestEmbeddingProvider().fail(true))) {
            assertEquals(List.of("chinese"), search.retrieve("中文回答", 5).stream().map(MemoryEntry::getId).toList());
        }
        assertEquals(0, vectorCount());
    }

    @Test
    void corruptVectorIsRecomputedRatherThanUsed() throws Exception {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        String text = "Redis cache";
        memory.store(fact("redis", text));
        try (MemoryRetriever search = retriever(memory, new MemoryTestEmbeddingProvider())) { search.retrieve("Redis", 5); }
        execute("UPDATE long_term_memory_embeddings SET vector=x'00'");
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider();
        try (MemoryRetriever search = retriever(memory, provider)) { search.retrieve("Redis", 5); }
        assertEquals(1, provider.calls(text));
        assertEquals(1, memory.readEmbeddings(List.of(memory.retrieve("redis").orElseThrow()), provider.space()).size());
    }

    @Test
    void sameDimensionModelRevisionDoesNotReuseOldSpace() {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        String text = "Redis cache configuration";
        memory.store(fact("redis", text));
        try (MemoryRetriever search = retriever(memory, new MemoryTestEmbeddingProvider())) { search.retrieve("Redis", 5); }
        MemoryTestEmbeddingProvider revised = new MemoryTestEmbeddingProvider(2, "revision-2");
        try (MemoryRetriever search = retriever(memory, revised)) { search.retrieve("Redis", 5); }
        assertEquals(1, revised.calls(text));
    }

    @Test
    void wrongDimensionAndNonFiniteVectorsAreCacheMisses() throws Exception {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        MemoryEntry entry = fact("redis", "Redis cache");
        memory.store(entry);
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider();
        memory.writeEmbeddings(List.of(entry), provider.space(), List.of(new float[]{1, 0}));
        execute("UPDATE long_term_memory_embeddings SET dimension=3");
        assertTrue(memory.readEmbeddings(List.of(entry), provider.space()).isEmpty());
        execute("UPDATE long_term_memory_embeddings SET dimension=2, vector=x'0000C07F00000000'");
        assertTrue(memory.readEmbeddings(List.of(entry), provider.space()).isEmpty());
        memory.writeEmbeddings(List.of(entry), provider.space(), List.of(new float[]{Float.NaN, 0}));
        assertTrue(memory.readEmbeddings(List.of(entry), provider.space()).isEmpty());
    }

    @Test
    void lateVectorCannotOverwriteUpdatedOrDeletedFact() throws Exception {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        MemoryEntry old = fact("redis", "Redis old configuration");
        MemoryEntry current = fact("redis", "Redis current configuration");
        memory.store(old);
        memory.store(current);
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider();
        memory.writeEmbeddings(List.of(current), provider.space(), List.of(new float[]{0, 1}));
        memory.writeEmbeddings(List.of(old), provider.space(), List.of(new float[]{1, 0}));
        assertArrayEquals(new float[]{0, 1}, memory.readEmbeddings(List.of(current), provider.space()).get("redis"));
        assertTrue(memory.readEmbeddings(List.of(old), provider.space()).isEmpty());
        memory.delete("redis");
        memory.writeEmbeddings(List.of(current), provider.space(), List.of(new float[]{1, 0}));
        assertEquals(0, vectorCount());
    }

    @Test
    void supersededAndClearedFactsDoNotLeaveUsableVectors() throws Exception {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        MemoryEntry old = fact("old", "Redis current configuration");
        MemoryEntry replacement = fact("new", "Postgres current configuration");
        memory.store(old);
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider();
        memory.writeEmbeddings(List.of(old), provider.space(), List.of(new float[]{1, 0}));
        assertTrue(memory.supersede("old", replacement));
        memory.writeEmbeddings(List.of(old), provider.space(), List.of(new float[]{1, 0}));
        assertEquals(0, vectorCount());
        memory.writeEmbeddings(List.of(replacement), provider.space(), List.of(new float[]{0, 1}));
        memory.clear();
        assertEquals(0, vectorCount());
        assertTrue(memory.lexicalMatches("Postgres", null, 10).isEmpty());
    }

    @Test
    void cacheWriteFailurePreservesFactAndCurrentSemanticResult() throws Exception {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        String text = "Default language preference";
        memory.store(fact("preference", text));
        execute("""
                CREATE TRIGGER reject_vector BEFORE INSERT ON long_term_memory_embeddings
                BEGIN SELECT RAISE(ABORT, 'test cache failure'); END
                """);
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector(text, 1, 0).vector("中文回复", 1, 0);
        try (MemoryRetriever search = retriever(memory, provider)) {
            assertEquals("preference", search.retrieve("中文回复", 5).get(0).getId());
            assertEquals("preference", search.retrieve("中文回复", 5).get(0).getId());
        }
        assertEquals(1, memory.size());
        assertEquals(1, provider.calls(text));
        assertEquals(0, vectorCount());
    }

    @Test
    void lexicalIndexTracksUpdatesAcrossInstancesAndEscapesQueryOperators() {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        memory.store(fact("language", "Java backend"));
        LongTermMemory other = new LongTermMemory(directory.toFile());
        other.store(fact("language", "Python backend"));
        try (MemoryRetriever search = retriever(memory, new MemoryTestEmbeddingProvider().fail(true))) {
            assertTrue(search.retrieve("Java", 5).isEmpty());
            assertEquals("language", search.retrieve("Python\" OR (backend*):", 5).get(0).getId());
            assertTrue(search.retrieve("!!!", 5).isEmpty());
        }
    }

    @Test
    void bothChannelsRespectProjectAndActiveFiltersAndInjectionRemainsBounded() {
        LongTermMemory memory = new LongTermMemory(directory.toFile());
        memory.store(new MemoryEntry("other", "Redis private settings", MemoryEntry.MemoryType.FACT, NOW,
                Map.of("scope", "project", "project", "/other"), 5));
        for (int i = 0; i < 15; i++) memory.store(fact("global-" + i, "Redis global settings " + i));
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider();
        try (MemoryRetriever search = retriever(memory, provider)) {
            String context = search.buildContextForQuery("Redis", 10000, "/current");
            assertFalse(context.contains("private"));
            assertEquals(10, context.lines().filter(line -> line.startsWith("- [FACT]")).count());
        }
        assertEquals(0, provider.calls("Redis private settings"));
    }

    private void execute(String... statements) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("memory.db"));
             var statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    private int vectorCount() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("memory.db"));
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM long_term_memory_embeddings")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
