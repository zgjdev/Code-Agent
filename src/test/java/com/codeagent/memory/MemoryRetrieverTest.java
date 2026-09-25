package com.codeagent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRetrieverTest {
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");

    @TempDir Path tempDir;
    private LongTermMemory longTerm;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        longTerm = new LongTermMemory(tempDir.toFile());
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider().fail(true);
        retriever = new MemoryRetriever(
                longTerm,
                new MemoryEmbeddingCache(provider),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldRetrieveFromLongTerm() {
        longTerm.store(new MemoryEntry("f1", "用户偏好：喜欢用Spring Boot",
                MemoryEntry.MemoryType.FACT, null, 10));
        assertFalse(retriever.retrieve("Spring Boot", 5).isEmpty());
    }

    @Test
    void shouldBuildContextForQuery() {
        longTerm.store(new MemoryEntry("f1", "项目路径: /home/dev/myapp",
                MemoryEntry.MemoryType.FACT, null, 10));
        String context = retriever.buildContextForQuery("项目路径", 200);
        assertFalse(context.isEmpty());
        assertTrue(context.contains("/home/dev/myapp"));
    }

    @Test
    void shouldNotTreatCurrentConversationAsRetrievableMemory() {
        longTerm.store(new MemoryEntry("f1", "用户偏好使用中文交流",
                MemoryEntry.MemoryType.FACT, null, 10));
        assertTrue(retriever.buildContextForQuery(
                "新建一个第六期的文件夹，里面有一个test.txt文件", 200).isEmpty());
    }

    @Test
    void shouldReturnEmptyForNoMatch() {
        assertTrue(retriever.retrieve("Spring Boot", 5).isEmpty());
    }

    @Test
    void shouldRetrieveChineseByPhraseFragments() {
        longTerm.store(new MemoryEntry("f1", "用户偏好使用Java开发",
                MemoryEntry.MemoryType.FACT, null, 10));
        var results = retriever.retrieve("偏好设置", 5);
        assertFalse(results.isEmpty());
        assertEquals("f1", results.get(0).getId());
    }

    @Test
    void shouldInjectOnlyGlobalAndCurrentProjectLongTermMemory() {
        longTerm.store(new MemoryEntry("global", "默认用中文回答", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 10));
        longTerm.store(new MemoryEntry("current", "当前项目使用 Java 17", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/current"), 10));
        longTerm.store(new MemoryEntry("other", "其他项目使用 Python", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/other"), 10));
        String context = retriever.buildContextForQuery("项目 使用", 300, "/repo/current");
        assertTrue(context.contains("当前项目使用 Java 17"));
        assertFalse(context.contains("其他项目使用 Python"));
    }

    @Test
    void semanticRetrievalRecallsParaphraseWithoutSharedKeywords() {
        String memoryText = "默认使用中文回答用户";
        String query = "后续都用汉语和我沟通";
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector(memoryText, 1f, 0f)
                .vector(query, 1f, 0f);
        MemoryRetriever semanticRetriever = new MemoryRetriever(
                longTerm,
                new MemoryEmbeddingCache(provider),
                Clock.fixed(NOW, ZoneOffset.UTC));
        longTerm.store(new MemoryEntry("f1", memoryText, MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 8));

        var results = semanticRetriever.retrieveLongTerm(query, 5, "/repo/current");

        assertEquals(1, results.size());
        assertEquals("f1", results.get(0).getId());
    }

    @Test
    void unrelatedSemanticResultBelowThresholdIsNotInjected() {
        String memoryText = "默认使用中文回答用户";
        String query = "修复 Maven 编译失败";
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector(memoryText, 1f, 0f)
                .vector(query, 0f, 1f);
        MemoryRetriever semanticRetriever = new MemoryRetriever(
                longTerm,
                new MemoryEmbeddingCache(provider),
                Clock.fixed(NOW, ZoneOffset.UTC));
        longTerm.store(new MemoryEntry("f1", memoryText, MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 8));

        assertTrue(semanticRetriever.retrieveLongTerm(query, 5, "/repo/current").isEmpty());
    }

    @Test
    void decayUsesThirtyDayHalfLifeAboveSixtyPercentFloor() {
        assertEquals(1.0, retriever.decayFactor(NOW), 0.000001);
        assertEquals(0.8, retriever.decayFactor(NOW.minusSeconds(30L * 86400L)), 0.000001);
        assertEquals(0.7, retriever.decayFactor(NOW.minusSeconds(60L * 86400L)), 0.000001);
        assertEquals(0.65, retriever.decayFactor(NOW.minusSeconds(90L * 86400L)), 0.000001);
        assertTrue(retriever.decayFactor(NOW.minusSeconds(3650L * 86400L)) >= 0.6);
        assertEquals(0.6, retriever.decayFactor(NOW.minusSeconds(3650L * 86400L)), 0.000001);
    }

    @Test
    void lastConfirmedAtOverridesOldCreationTimestamp() {
        MemoryEntry entry = new MemoryEntry(
                "confirmed",
                "默认使用中文回答用户",
                MemoryEntry.MemoryType.FACT,
                NOW.minusSeconds(365L * 86400L),
                Map.of("scope", "global", "lastConfirmedAt", NOW.toString()),
                8);

        assertEquals(1.0, retriever.decayFactor(entry), 0.000001);
    }

    @Test
    void legacyMemoryFallsBackToCreationTimestamp() {
        MemoryEntry entry = new MemoryEntry(
                "legacy",
                "默认使用中文回答用户",
                MemoryEntry.MemoryType.FACT,
                NOW.minusSeconds(30L * 86400L),
                Map.of("scope", "global"),
                8);

        assertEquals(0.8, retriever.decayFactor(entry), 0.000001);
    }

    @Test
    void futureConfirmationDoesNotIncreaseDecayAboveOne() {
        assertEquals(1.0, retriever.decayFactor(NOW.plusSeconds(7L * 86400L)), 0.000001);
    }

    @Test
    void oldHighlyRelevantMemoryStillBeatsClearlyWeakNewMemoryAtFloor() {
        String query = "Java 17";
        String oldText = "当前项目使用 Java 17";
        String newText = "默认使用中文回答用户";
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector(query, 1f, 0f)
                .vector(oldText, 1f, 0f)
                .vector(newText, 0.7f, (float) Math.sqrt(1.0 - 0.49));
        MemoryRetriever semanticRetriever = new MemoryRetriever(
                longTerm,
                new MemoryEmbeddingCache(provider),
                Clock.fixed(NOW, ZoneOffset.UTC));
        longTerm.store(new MemoryEntry("old", oldText, MemoryEntry.MemoryType.FACT,
                NOW.minusSeconds(3650L * 86400L), Map.of("scope", "global"), 8));
        longTerm.store(new MemoryEntry("new", newText, MemoryEntry.MemoryType.FACT,
                NOW, Map.of("scope", "global"), 8));

        var results = semanticRetriever.retrieveLongTerm(query, 5, "/repo/current");

        assertEquals("old", results.get(0).getId());
    }

    @Test
    void recentModeratelyLowerRelevanceCanBeatVeryOldMemory() {
        String query = "偏好语言";
        String oldText = "偏好语言 Java";
        String newText = "偏好语言 Python";
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector(query, 1f, 0f)
                .vector(oldText, 1f, 0f)
                .vector(newText, 0.8f, 0.6f);
        MemoryRetriever semanticRetriever = new MemoryRetriever(
                longTerm,
                new MemoryEmbeddingCache(provider),
                Clock.fixed(NOW, ZoneOffset.UTC));
        longTerm.store(new MemoryEntry("old", oldText, MemoryEntry.MemoryType.FACT,
                NOW.minusSeconds(3650L * 86400L), Map.of("scope", "global"), 8));
        longTerm.store(new MemoryEntry("new", newText, MemoryEntry.MemoryType.FACT,
                NOW, Map.of("scope", "global"), 8));

        var results = semanticRetriever.retrieveLongTerm(query, 5, "/repo/current");

        assertEquals("new", results.get(0).getId());
    }

    @Test
    void writeCandidateRankingIgnoresTimeDecay() {
        String query = "偏好语言";
        String oldText = "偏好语言 Java";
        String newText = "偏好语言 Python";
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector(query, 1f, 0f)
                .vector(oldText, 1f, 0f)
                .vector(newText, 0.8f, 0.6f);
        MemoryRetriever semanticRetriever = new MemoryRetriever(
                longTerm,
                new MemoryEmbeddingCache(provider),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MemoryEntry oldEntry = new MemoryEntry("old", oldText, MemoryEntry.MemoryType.FACT,
                NOW.minusSeconds(3650L * 86400L), Map.of("scope", "global"), 8);
        MemoryEntry newEntry = new MemoryEntry("new", newText, MemoryEntry.MemoryType.FACT,
                NOW, Map.of("scope", "global"), 8);
        longTerm.store(oldEntry);
        longTerm.store(newEntry);

        var candidates = semanticRetriever.retrieveWriteCandidates(
                query, 2, longTerm.getActiveVisible("/repo/current"), MemoryEntry.MemoryType.FACT);

        assertEquals("old", candidates.get(0).getId());
    }

    @Test
    void retrievalDoesNotRefreshLastConfirmedAt() {
        Instant confirmedAt = NOW.minusSeconds(60L * 86400L);
        longTerm.store(new MemoryEntry(
                "stable",
                "当前项目使用 Java 17",
                MemoryEntry.MemoryType.FACT,
                NOW.minusSeconds(365L * 86400L),
                Map.of("scope", "global", "lastConfirmedAt", confirmedAt.toString()),
                8));

        assertFalse(retriever.retrieveLongTerm("Java 17", 5, "/repo/current").isEmpty());

        MemoryEntry stored = longTerm.retrieve("stable").orElseThrow();
        assertEquals(confirmedAt, LongTermMemory.lastConfirmedAtOf(stored));
    }

    @Test
    void oversizedTopResultDoesNotStarveSmallerResult() {
        longTerm.store(new MemoryEntry("large", "项目大型约定", MemoryEntry.MemoryType.FACT,
                NOW, Map.of("scope", "global"), 500));
        longTerm.store(new MemoryEntry("small", "项目简短约定", MemoryEntry.MemoryType.FACT,
                NOW.minusSeconds(86400L), Map.of("scope", "global"), 5));

        String context = retriever.buildContextForQuery("项目", 10, "/repo/current");

        assertFalse(context.contains("大型约定"));
        assertTrue(context.contains("简短约定"));
    }

    @Test
    void supersededMemoryIsExcludedFromNormalRetrieval() {
        MemoryEntry oldEntry = new MemoryEntry("old", "用户偏好 Java", MemoryEntry.MemoryType.FACT,
                NOW.minusSeconds(86400L), Map.of("scope", "global"), 5);
        MemoryEntry newEntry = new MemoryEntry("new", "用户偏好 Python", MemoryEntry.MemoryType.FACT,
                NOW, Map.of("scope", "global"), 5);
        longTerm.store(oldEntry);
        assertTrue(longTerm.supersede("old", newEntry));

        assertTrue(retriever.retrieveLongTerm("Java", 5, "/repo/current").isEmpty());
    }
}
