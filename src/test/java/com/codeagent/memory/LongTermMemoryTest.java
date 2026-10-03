package com.codeagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryTest {
    @TempDir
    Path tempDir;

    private LongTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new LongTermMemory(tempDir.toFile());
    }

    @Test
    void shouldStoreAndRetrieve() {
        MemoryEntry entry = new MemoryEntry("fact-1", "项目使用Java 17", MemoryEntry.MemoryType.FACT, null, 10);
        memory.store(entry);

        assertTrue(memory.retrieve("fact-1").isPresent());
        assertEquals("项目使用Java 17", memory.retrieve("fact-1").get().getContent());
    }

    @Test
    void shouldDeduplicateSameContent() {
        MemoryEntry entry1 = new MemoryEntry("fact-1", "相同内容", MemoryEntry.MemoryType.FACT, null, 5);
        MemoryEntry entry2 = new MemoryEntry("fact-2", "相同内容", MemoryEntry.MemoryType.FACT, null, 5);

        memory.store(entry1);
        memory.store(entry2);

        assertEquals(1, memory.size());
    }

    @Test
    void shouldDeduplicateNormalizedFormattingVariantsInSameDomain() {
        Map<String, String> domain = Map.of(
                "scope", "project",
                "project", "/repo/current"
        );
        memory.store(new MemoryEntry(
                "fact-1",
                "  当前项目使用 Ｊａｖａ １７。\n",
                MemoryEntry.MemoryType.FACT,
                domain,
                10
        ));
        memory.store(new MemoryEntry(
                "fact-2",
                "当前项目使用java17.",
                MemoryEntry.MemoryType.FACT,
                domain,
                10
        ));

        assertEquals(1, memory.size());
        assertTrue(memory.retrieve("fact-1").isPresent());
    }

    @Test
    void shouldLeaveNaturalLanguageVariantsForWriteResolver() {
        Map<String, String> domain = Map.of(
                "scope", "project",
                "project", "/repo/current"
        );
        memory.store(new MemoryEntry(
                "fact-1",
                "当前项目使用Java17开发",
                MemoryEntry.MemoryType.FACT,
                domain,
                10
        ));
        memory.store(new MemoryEntry(
                "fact-2",
                "当前项目使用的是Java17开发",
                MemoryEntry.MemoryType.FACT,
                domain,
                10
        ));

        assertEquals(2, memory.size());
    }

    @Test
    void shouldNotDeduplicateAcrossMemoryTypes() {
        memory.store(new MemoryEntry(
                "fact-1",
                "默认用中文回答",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"),
                5
        ));
        memory.store(new MemoryEntry(
                "summary-1",
                "默认用中文回答",
                MemoryEntry.MemoryType.SUMMARY,
                Map.of("scope", "global"),
                5
        ));

        assertEquals(2, memory.size());
    }

    @Test
    void shouldNotDeduplicateAcrossGlobalAndProjectScopes() {
        memory.store(new MemoryEntry(
                "global",
                "默认用中文回答",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"),
                5
        ));
        memory.store(new MemoryEntry(
                "project",
                "默认用中文回答",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/current"),
                5
        ));

        assertEquals(2, memory.size());
    }

    @Test
    void shouldNotDeduplicateAcrossProjects() {
        memory.store(new MemoryEntry(
                "project-a",
                "项目使用Java17",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/a"),
                5
        ));
        memory.store(new MemoryEntry(
                "project-b",
                "项目使用Java17",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/b"),
                5
        ));

        assertEquals(2, memory.size());
    }

    @Test
    void projectScopeWithoutProjectKeyShouldNotBeDeduplicated() {
        Map<String, String> incompleteDomain = Map.of("scope", "project");
        memory.store(new MemoryEntry(
                "fact-1",
                "项目使用Java17",
                MemoryEntry.MemoryType.FACT,
                incompleteDomain,
                5
        ));
        memory.store(new MemoryEntry(
                "fact-2",
                "项目使用Java17",
                MemoryEntry.MemoryType.FACT,
                incompleteDomain,
                5
        ));

        assertEquals(2, memory.size());
    }

    @Test
    void shouldKeepConflictingNumbersCodeMarkersAndMeaningfulWords() {
        Map<String, String> domain = Map.of(
                "scope", "project",
                "project", "/repo/current"
        );
        memory.store(new MemoryEntry(
                "java-17",
                "当前项目必须使用Java17构建",
                MemoryEntry.MemoryType.FACT,
                domain,
                8
        ));
        memory.store(new MemoryEntry(
                "java-21",
                "当前项目必须使用Java21构建",
                MemoryEntry.MemoryType.FACT,
                domain,
                8
        ));
        memory.store(new MemoryEntry(
                "c",
                "代码示例使用C",
                MemoryEntry.MemoryType.FACT,
                domain,
                5
        ));
        memory.store(new MemoryEntry(
                "cpp",
                "代码示例使用C++",
                MemoryEntry.MemoryType.FACT,
                domain,
                5
        ));
        memory.store(new MemoryEntry(
                "simple",
                "默认使用中文回答所有技术问题",
                MemoryEntry.MemoryType.FACT,
                domain,
                8
        ));
        memory.store(new MemoryEntry(
                "traditional",
                "默认使用繁体中文回答所有技术问题",
                MemoryEntry.MemoryType.FACT,
                domain,
                8
        ));
        memory.store(new MemoryEntry(
                "allow-auto-commit",
                "当前项目允许自动提交代码",
                MemoryEntry.MemoryType.FACT,
                domain,
                8
        ));
        memory.store(new MemoryEntry(
                "deny-auto-commit",
                "当前项目不允许自动提交代码",
                MemoryEntry.MemoryType.FACT,
                domain,
                8
        ));
        memory.store(new MemoryEntry(
                "thumbs-up",
                "默认用👍表示同意",
                MemoryEntry.MemoryType.FACT,
                domain,
                5
        ));
        memory.store(new MemoryEntry(
                "thumbs-down",
                "默认用👎表示同意",
                MemoryEntry.MemoryType.FACT,
                domain,
                5
        ));

        assertEquals(10, memory.size());
    }

    @Test
    void concurrentDuplicateStoresShouldBeAtomic() throws Exception {
        int workers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        Map<String, String> domain = Map.of(
                "scope", "project",
                "project", "/repo/current"
        );
        try {
            for (int i = 0; i < workers; i++) {
                int index = i;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        memory.store(new MemoryEntry(
                                "fact-" + index,
                                "并发保存时也只能留下同一条事实",
                                MemoryEntry.MemoryType.FACT,
                                domain,
                                7
                        ));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, memory.size());
        assertEquals(7, memory.getTokenCount());
        assertEquals(1, new LongTermMemory(tempDir.toFile()).size());
    }

    @Test
    void replacingSameIdShouldKeepTokenCountAccurate() {
        memory.store(new MemoryEntry(
                "fact-1",
                "项目使用Java17",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/current"),
                5
        ));
        memory.store(new MemoryEntry(
                "fact-1",
                "项目使用Python开发",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/current"),
                9
        ));

        assertEquals(1, memory.size());
        assertEquals(9, memory.getTokenCount());
        assertEquals("项目使用Python开发", memory.retrieve("fact-1").orElseThrow().getContent());
    }

    @Test
    void shouldSearchByKeywords() {
        memory.store(new MemoryEntry("f1", "用户偏好使用IntelliJ IDEA", MemoryEntry.MemoryType.FACT, null, 10));
        memory.store(new MemoryEntry("f2", "项目路径: /home/user/project", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("IntelliJ", 5);
        assertEquals(1, results.size());
    }

    @Test
    void shouldSearchByMultipleKeywords() {
        memory.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("Java 偏好", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldSearchChineseWithoutRelyingOnSpaces() {
        memory.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("偏好设置", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldDeleteEntry() {
        memory.store(new MemoryEntry("f1", "测试内容", MemoryEntry.MemoryType.FACT, null, 5));
        assertTrue(memory.delete("f1"));
        assertEquals(0, memory.size());
    }

    @Test
    void shouldFilterByType() {
        memory.store(new MemoryEntry("f1", "事实1", MemoryEntry.MemoryType.FACT, null, 5));
        memory.store(new MemoryEntry("s1", "摘要1", MemoryEntry.MemoryType.SUMMARY, null, 5));

        var facts = memory.getByType(MemoryEntry.MemoryType.FACT);
        assertEquals(1, facts.size());
    }

    @Test
    void shouldPersistAndReload() {
        memory.store(new MemoryEntry("f1", "持久化测试内容", MemoryEntry.MemoryType.FACT, null, 10));
        memory.store(new MemoryEntry("s1", "摘要测试", MemoryEntry.MemoryType.SUMMARY, null, 8));

        // 创建新实例，从磁盘加载
        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.retrieve("f1").isPresent());
    }

    @Test
    void shouldPreserveTimestampAfterReload() {
        Instant timestamp = Instant.parse("2026-04-20T12:34:56Z");
        memory.store(new MemoryEntry("f1", "带时间戳的事实", MemoryEntry.MemoryType.FACT, timestamp, null, 10));

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(timestamp, reloaded.retrieve("f1").orElseThrow().getTimestamp());
    }

    @Test
    void shouldFilterProjectScopedMemories() {
        memory.store(new MemoryEntry("global", "默认用中文回答", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 10));
        memory.store(new MemoryEntry("project-a", "项目A使用 Java 17", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/a"), 10));
        memory.store(new MemoryEntry("project-b", "项目B使用 Python", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/b"), 10));

        var visible = memory.getAll("/repo/a");

        assertEquals(2, visible.size());
        assertTrue(visible.stream().anyMatch(entry -> entry.getId().equals("global")));
        assertTrue(visible.stream().anyMatch(entry -> entry.getId().equals("project-a")));
        assertTrue(visible.stream().noneMatch(entry -> entry.getId().equals("project-b")));
    }

    @Test
    void shouldPersistSupersededLifecycleAndHideOldEntryFromActiveView() {
        MemoryEntry oldEntry = new MemoryEntry(
                "old",
                "用户偏好使用 Java",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"),
                5
        );
        MemoryEntry replacement = new MemoryEntry(
                "new",
                "用户偏好使用 Python",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"),
                5
        );
        memory.store(oldEntry);

        assertTrue(memory.supersede("old", replacement));
        assertEquals("superseded", LongTermMemory.statusOf(memory.retrieve("old").orElseThrow()));
        assertEquals("new", memory.retrieve("old").orElseThrow().getMetadata().get("supersededBy"));
        assertEquals("old", memory.retrieve("new").orElseThrow().getMetadata().get("supersedes"));
        assertEquals(List.of("new"), memory.getActiveVisible("/repo/current").stream()
                .map(MemoryEntry::getId).toList());

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals("superseded", LongTermMemory.statusOf(reloaded.retrieve("old").orElseThrow()));
        assertEquals(List.of("new"), reloaded.getActiveVisible("/repo/current").stream()
                .map(MemoryEntry::getId).toList());
    }

    @Test
    void newActiveMemoryInitializesLastConfirmedAtFromTimestamp() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        memory.store(new MemoryEntry(
                "fact-confirmed",
                "默认用中文回答",
                MemoryEntry.MemoryType.FACT,
                createdAt,
                Map.of("scope", "global"),
                5
        ));

        MemoryEntry stored = memory.retrieve("fact-confirmed").orElseThrow();
        assertEquals(createdAt, stored.getTimestamp());
        assertEquals(createdAt, LongTermMemory.lastConfirmedAtOf(stored));
        assertEquals(createdAt.toString(), stored.getMetadata().get("lastConfirmedAt"));
    }

    @Test
    void confirmRefreshesLastConfirmedAtWithoutChangingCreationTimestamp() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant confirmedAt = Instant.parse("2026-09-25T00:00:00Z");
        memory.store(new MemoryEntry(
                "fact-confirmed",
                "默认用中文回答",
                MemoryEntry.MemoryType.FACT,
                createdAt,
                Map.of("scope", "global"),
                5
        ));

        assertTrue(memory.confirm("fact-confirmed", confirmedAt));

        MemoryEntry confirmed = memory.retrieve("fact-confirmed").orElseThrow();
        assertEquals(createdAt, confirmed.getTimestamp());
        assertEquals(confirmedAt, LongTermMemory.lastConfirmedAtOf(confirmed));

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(confirmedAt,
                LongTermMemory.lastConfirmedAtOf(reloaded.retrieve("fact-confirmed").orElseThrow()));
    }

    @Test
    void confirmNeverMovesLastConfirmedAtBackward() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-09-25T00:00:00Z");
        Instant older = Instant.parse("2026-06-01T00:00:00Z");
        memory.store(new MemoryEntry(
                "fact-confirmed",
                "默认用中文回答",
                MemoryEntry.MemoryType.FACT,
                createdAt,
                Map.of("scope", "global", "lastConfirmedAt", newer.toString()),
                5
        ));

        assertTrue(memory.confirm("fact-confirmed", older));
        assertEquals(newer,
                LongTermMemory.lastConfirmedAtOf(memory.retrieve("fact-confirmed").orElseThrow()));
    }

    @Test
    void supersededMemoryCannotBeConfirmed() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        MemoryEntry oldEntry = new MemoryEntry(
                "old-confirm",
                "用户偏好 Java",
                MemoryEntry.MemoryType.FACT,
                createdAt,
                Map.of("scope", "global"),
                5
        );
        MemoryEntry replacement = new MemoryEntry(
                "new-confirm",
                "用户偏好 Python",
                MemoryEntry.MemoryType.FACT,
                Instant.parse("2026-09-01T00:00:00Z"),
                Map.of("scope", "global"),
                5
        );
        memory.store(oldEntry);
        assertTrue(memory.supersede("old-confirm", replacement));

        assertFalse(memory.confirm("old-confirm", Instant.parse("2026-09-25T00:00:00Z")));
    }

    @Test
    void invalidLastConfirmedAtFallsBackToTimestamp() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        MemoryEntry entry = new MemoryEntry(
                "legacy-invalid-confirmation",
                "项目使用 Java 17",
                MemoryEntry.MemoryType.FACT,
                createdAt,
                Map.of("scope", "global", "lastConfirmedAt", "not-an-instant"),
                5
        );

        assertEquals(createdAt, LongTermMemory.lastConfirmedAtOf(entry));
    }

    @Test
    void confirmRepairsInvalidPersistedConfirmationMetadata() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant confirmedAt = Instant.parse("2026-09-25T00:00:00Z");
        memory.store(new MemoryEntry(
                "repair-confirmation",
                "项目使用 Java 17",
                MemoryEntry.MemoryType.FACT,
                createdAt,
                Map.of("scope", "global", "lastConfirmedAt", "not-an-instant"),
                5
        ));

        assertTrue(memory.confirm("repair-confirmation", confirmedAt));

        MemoryEntry repaired = memory.retrieve("repair-confirmation").orElseThrow();
        assertEquals(confirmedAt, LongTermMemory.lastConfirmedAtOf(repaired));
        assertEquals(confirmedAt.toString(), repaired.getMetadata().get("lastConfirmedAt"));
    }

    @Test
    void legacyMemoriesWithoutScopeRemainGlobal() {
        MemoryEntry legacy = new MemoryEntry("legacy", "历史偏好", MemoryEntry.MemoryType.FACT, null, 10);

        assertEquals("global", LongTermMemory.scopeOf(legacy));
        assertTrue(LongTermMemory.isVisibleInProject(legacy, "/repo/current"));
    }

    @Test
    void independentInstancesShouldNotLoseEachOthersWrites() {
        LongTermMemory first = new LongTermMemory(tempDir.toFile());
        LongTermMemory second = new LongTermMemory(tempDir.toFile());

        first.store(new MemoryEntry(
                "first",
                "第一个进程写入的事实",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"),
                6
        ));
        second.store(new MemoryEntry(
                "second",
                "第二个进程写入的事实",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"),
                6
        ));

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.retrieve("first").isPresent());
        assertTrue(reloaded.retrieve("second").isPresent());
        assertTrue(first.retrieve("second").isPresent(),
                "已有实例也应从 SQLite 看到其他实例已提交的写入");
    }

    @Test
    void equivalentWritesAcrossIndependentInstancesShouldBeAtomic() throws Exception {
        LongTermMemory first = new LongTermMemory(tempDir.toFile());
        LongTermMemory second = new LongTermMemory(tempDir.toFile());
        Map<String, String> domain = Map.of(
                "scope", "project",
                "project", "/repo/current"
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successfulStores = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (first.storeIfNovel(new MemoryEntry(
                            "first-duplicate",
                            "并发跨实例保存时只保留这一条事实",
                            MemoryEntry.MemoryType.FACT,
                            domain,
                            8
                    ))) {
                        successfulStores.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (second.storeIfNovel(new MemoryEntry(
                            "second-duplicate",
                            "并发跨实例保存时只保留这一条事实",
                            MemoryEntry.MemoryType.FACT,
                            domain,
                            8
                    ))) {
                        successfulStores.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile());
        assertEquals(1, successfulStores.get());
        assertEquals(1, reloaded.size());
        assertEquals(8, reloaded.getTokenCount());
    }

    @Test
    void freshStorageUsesSqliteWithoutCreatingLegacyJson() {
        assertTrue(Files.exists(tempDir.resolve("memory.db")));
        assertFalse(Files.exists(tempDir.resolve("long_term_memory.json")));

        memory.store(new MemoryEntry(
                "sqlite-only",
                "新的长期记忆只写入 SQLite",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"),
                7
        ));

        assertTrue(Files.exists(tempDir.resolve("memory.db")));
        assertFalse(Files.exists(tempDir.resolve("long_term_memory.json")));
    }

    @Test
    void shouldMigrateLegacyJsonExactlyOnce() throws Exception {
        Path legacyDir = tempDir.resolve("legacy");
        Files.createDirectories(legacyDir);
        Path legacyJson = legacyDir.resolve("long_term_memory.json");
        Files.writeString(legacyJson, """
                [
                  {
                    "id": "legacy-1",
                    "content": "从旧 JSON 迁移的事实",
                    "type": "FACT",
                    "timestamp": "2026-01-01T00:00:00Z",
                    "metadata": {
                      "scope": "global"
                    },
                    "tokenCount": 9
                  }
                ]
                """);

        LongTermMemory migrated = new LongTermMemory(legacyDir.toFile());
        assertTrue(Files.exists(legacyDir.resolve("memory.db")));
        assertTrue(migrated.retrieve("legacy-1").isPresent());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"),
                migrated.retrieve("legacy-1").orElseThrow().getTimestamp());

        Files.writeString(legacyJson, """
                [
                  {
                    "id": "must-not-reimport",
                    "content": "迁移完成后不应再次导入 JSON",
                    "type": "FACT",
                    "timestamp": "2026-02-01T00:00:00Z",
                    "metadata": {
                      "scope": "global"
                    },
                    "tokenCount": 9
                  }
                ]
                """);

        LongTermMemory reopened = new LongTermMemory(legacyDir.toFile());
        assertTrue(reopened.retrieve("legacy-1").isPresent());
        assertFalse(reopened.retrieve("must-not-reimport").isPresent());
        assertEquals(1, reopened.size());
    }

    @Test
    void legacyDuplicateMigrationMergesConfirmationAndRemapsChainsInAnyOrder() throws Exception {
        List<List<String>> orders = List.of(
                List.of("active-a", "active-b", "active-c"),
                List.of("active-a", "active-c", "active-b"),
                List.of("active-b", "active-a", "active-c"),
                List.of("active-b", "active-c", "active-a"),
                List.of("active-c", "active-a", "active-b"),
                List.of("active-c", "active-b", "active-a"));
        Map<String, Map<String, Object>> duplicates = Map.of(
                "active-a", legacyEntry("active-a", "Java preference", Map.of(
                        "scope", "global", "lastConfirmedAt", "2026-02-01T00:00:00Z")),
                "active-b", legacyEntry("active-b", "Ｊａｖａ preference。", Map.of(
                        "scope", "global", "lastConfirmedAt", "2026-04-01T00:00:00Z")),
                "active-c", legacyEntry("active-c", "java preference", Map.of(
                        "scope", "global", "lastConfirmedAt", "2026-03-01T00:00:00Z",
                        "supersedes", "history-1")));

        for (int index = 0; index < orders.size(); index++) {
            List<String> order = orders.get(index);
            Path legacyDir = tempDir.resolve("duplicate-order-" + index);
            Files.createDirectories(legacyDir);
            var entries = new java.util.ArrayList<Map<String, Object>>();
            entries.add(duplicates.get(order.get(0)));
            entries.add(legacyEntry("history-0", "earliest preference", Map.of(
                    "scope", "global", "status", "superseded", "supersededBy", "history-1")));
            entries.add(duplicates.get(order.get(1)));
            entries.add(duplicates.get(order.get(2)));
            entries.add(legacyEntry("history-1", "older preference", Map.of(
                    "scope", "global", "status", "superseded", "supersedes", "history-0",
                    "supersededBy", "active-c")));
            Files.writeString(legacyDir.resolve("long_term_memory.json"),
                    new ObjectMapper().writeValueAsString(entries));

            LongTermMemory migrated = new LongTermMemory(legacyDir.toFile());
            assertEquals(3, migrated.size(), order.toString());
            assertEquals(1, migrated.getActiveVisible(null).size());
            MemoryEntry retained = migrated.getActiveVisible(null).get(0);
            assertEquals(Instant.parse("2026-04-01T00:00:00Z"),
                    LongTermMemory.lastConfirmedAtOf(retained), order.toString());
            assertEquals("history-1", retained.getMetadata().get("supersedes"), order.toString());
            assertEquals(retained.getId(), migrated.retrieve("history-1").orElseThrow()
                    .getMetadata().get("supersededBy"), order.toString());
            assertEquals("history-0", migrated.retrieve("history-1").orElseThrow()
                    .getMetadata().get("supersedes"));
            assertEquals("history-1", migrated.retrieve("history-0").orElseThrow()
                    .getMetadata().get("supersededBy"));
            assertMigrationReferencesResolve(migrated);
            assertTrue(Files.exists(legacyDir.resolve("long_term_memory.json.migrated.bak")));
            assertEquals("migrated", migrationMarker(legacyDir));

            LongTermMemory reopened = new LongTermMemory(legacyDir.toFile());
            assertEquals(retained.getMetadata(), reopened.retrieve(retained.getId())
                    .orElseThrow().getMetadata());
            assertMigrationReferencesResolve(reopened);
        }
    }

    @Test
    void legacyDuplicateMigrationMustNotCreateSelfReferences() throws Exception {
        Path legacyDir = tempDir.resolve("duplicate-self-reference");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("long_term_memory.json"),
                new ObjectMapper().writeValueAsString(List.of(
                        legacyEntry("active-a", "Java preference", Map.of(
                                "scope", "global", "supersedes", "active-b",
                                "supersededBy", "active-c")),
                        legacyEntry("active-b", "java preference", Map.of(
                                "scope", "global", "supersedes", "active-c",
                                "supersededBy", "active-a")),
                        legacyEntry("active-c", "Ｊａｖａ preference。", Map.of(
                                "scope", "global", "supersedes", "active-a",
                                "supersededBy", "active-b")))));

        LongTermMemory migrated = new LongTermMemory(legacyDir.toFile());

        assertEquals(1, migrated.size());
        MemoryEntry retained = migrated.getActiveVisible(null).get(0);
        assertFalse(retained.getMetadata().containsKey("supersedes"));
        assertFalse(retained.getMetadata().containsKey("supersededBy"));
        assertMigrationReferencesResolve(migrated);
    }

    @Test
    void legacyDuplicateMigrationPrefersExistingHistoryOverDanglingRelation() throws Exception {
        Path legacyDir = tempDir.resolve("duplicate-valid-history");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("long_term_memory.json"),
                new ObjectMapper().writeValueAsString(List.of(
                        legacyEntry("active-a", "Java preference", Map.of(
                                "scope", "global", "supersedes", "missing-history")),
                        legacyEntry("active-b", "java preference", Map.of(
                                "scope", "global", "supersedes", "history")),
                        legacyEntry("history", "older preference", Map.of(
                                "scope", "global", "status", "superseded",
                                "supersededBy", "active-b")))));

        LongTermMemory migrated = new LongTermMemory(legacyDir.toFile());

        assertEquals(2, migrated.size());
        MemoryEntry retained = migrated.getActiveVisible(null).get(0);
        assertEquals("history", retained.getMetadata().get("supersedes"));
        assertEquals(retained.getId(), migrated.retrieve("history").orElseThrow()
                .getMetadata().get("supersededBy"));
        assertMigrationReferencesResolve(migrated);
    }

    @Test
    void legacyDuplicateMigrationRemapsRelationsBetweenDuplicateGroups() throws Exception {
        Path legacyDir = tempDir.resolve("duplicate-group-relations");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("long_term_memory.json"),
                new ObjectMapper().writeValueAsString(List.of(
                        legacyEntry("current-b", "Java preference", Map.of(
                                "scope", "global", "supersedes", "previous-b")),
                        legacyEntry("previous-a", "Python preference", Map.of("scope", "global")),
                        legacyEntry("current-a", "java preference", Map.of("scope", "global")),
                        legacyEntry("previous-b", "python preference", Map.of(
                                "scope", "global", "supersededBy", "current-b")))));

        LongTermMemory migrated = new LongTermMemory(legacyDir.toFile());

        assertEquals(2, migrated.size());
        assertEquals("previous-a", migrated.retrieve("current-a").orElseThrow()
                .getMetadata().get("supersedes"));
        assertEquals("current-a", migrated.retrieve("previous-a").orElseThrow()
                .getMetadata().get("supersededBy"));
        assertMigrationReferencesResolve(migrated);
    }

    @Test
    void legacyMigrationFailureRollsBackRowsAndMarkerAndCanRetry() throws Exception {
        Path legacyDir = tempDir.resolve("migration-rollback");
        LongTermMemory existing = new LongTermMemory(legacyDir.toFile());
        existing.store(new MemoryEntry("preexisting", "unrelated existing fact",
                MemoryEntry.MemoryType.FACT, Map.of("scope", "global"), 5));
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + legacyDir.resolve("memory.db").toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM memory_meta WHERE key='legacy_json_migration'");
            statement.execute("""
                    CREATE TRIGGER fail_legacy_insert BEFORE INSERT ON long_term_memories
                    WHEN NEW.id='fail-import'
                    BEGIN SELECT RAISE(ABORT, 'test migration failure'); END
                    """);
        }
        Path legacyJson = legacyDir.resolve("long_term_memory.json");
        Files.writeString(legacyJson, new ObjectMapper().writeValueAsString(List.of(
                legacyEntry("first-import", "first imported fact", Map.of("scope", "global")),
                legacyEntry("fail-import", "second imported fact", Map.of("scope", "global")))));

        assertThrows(IllegalStateException.class, () -> new LongTermMemory(legacyDir.toFile()));
        assertEquals(1, existing.size());
        assertTrue(existing.retrieve("first-import").isEmpty());
        assertNull(migrationMarker(legacyDir));
        assertTrue(Files.exists(legacyJson));
        assertFalse(Files.exists(legacyDir.resolve("long_term_memory.json.migrated.bak")));

        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + legacyDir.resolve("memory.db").toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER fail_legacy_insert");
        }
        LongTermMemory migrated = new LongTermMemory(legacyDir.toFile());
        assertEquals(3, migrated.size());
        assertEquals("migrated", migrationMarker(legacyDir));
        assertTrue(Files.exists(legacyDir.resolve("long_term_memory.json.migrated.bak")));
    }

    @Test
    void legacyDuplicateMigrationRejectsConflictingHistoryWithoutCommitting() throws Exception {
        Path legacyDir = tempDir.resolve("duplicate-conflicting-history");
        Files.createDirectories(legacyDir);
        Path legacyJson = legacyDir.resolve("long_term_memory.json");
        Files.writeString(legacyJson, new ObjectMapper().writeValueAsString(List.of(
                legacyEntry("active-a", "Java preference", Map.of(
                        "scope", "global", "supersedes", "history-a")),
                legacyEntry("active-b", "java preference", Map.of(
                        "scope", "global", "supersedes", "history-b")),
                legacyEntry("history-a", "older Java preference", Map.of(
                        "scope", "global", "status", "superseded", "supersededBy", "active-a")),
                legacyEntry("history-b", "older Python preference", Map.of(
                        "scope", "global", "status", "superseded", "supersededBy", "active-b")))));

        assertThrows(IllegalStateException.class, () -> new LongTermMemory(legacyDir.toFile()));

        assertNull(migrationMarker(legacyDir));
        assertTrue(Files.exists(legacyJson));
        assertFalse(Files.exists(legacyDir.resolve("long_term_memory.json.migrated.bak")));
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + legacyDir.resolve("memory.db").toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM long_term_memories")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
    }

    @Test
    void legacySameIdContentConflictMustPreserveDatabaseFactsAndRemainUnmigrated() throws Exception {
        Path legacyDir = tempDir.resolve("same-id-content-conflict");
        LongTermMemory existing = new LongTermMemory(legacyDir.toFile());
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        assertTrue(existing.storeIfNovel(new MemoryEntry("a", "Java preference",
                MemoryEntry.MemoryType.FACT, createdAt, Map.of("scope", "global"), 5)));
        assertTrue(existing.storeIfNovel(new MemoryEntry("b", "Python preference",
                MemoryEntry.MemoryType.FACT, createdAt, Map.of("scope", "global"), 5)));
        MemoryEntry originalJava = existing.retrieve("a").orElseThrow();
        MemoryEntry originalPython = existing.retrieve("b").orElseThrow();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + legacyDir.resolve("memory.db").toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM memory_meta WHERE key='legacy_json_migration'");
        }
        Path legacyJson = legacyDir.resolve("long_term_memory.json");
        String legacyContent = new ObjectMapper().writeValueAsString(List.of(
                legacyEntry("a", "Python preference", Map.of("scope", "global"))));
        Files.writeString(legacyJson, legacyContent);

        assertThrows(IllegalStateException.class, () -> new LongTermMemory(legacyDir.toFile()));

        assertEquals(2, existing.size());
        MemoryEntry preservedJava = existing.retrieve("a").orElseThrow();
        MemoryEntry preservedPython = existing.retrieve("b").orElseThrow();
        assertEquals(originalJava.getContent(), preservedJava.getContent());
        assertEquals(originalJava.getMetadata(), preservedJava.getMetadata());
        assertEquals(originalJava.getTimestamp(), preservedJava.getTimestamp());
        assertEquals(originalPython.getContent(), preservedPython.getContent());
        assertEquals(originalPython.getMetadata(), preservedPython.getMetadata());
        assertEquals(originalPython.getTimestamp(), preservedPython.getTimestamp());
        assertEquals(10, existing.getTokenCount());
        assertNull(migrationMarker(legacyDir));
        assertTrue(Files.exists(legacyJson));
        assertEquals(legacyContent, Files.readString(legacyJson));
        assertFalse(Files.exists(legacyDir.resolve("long_term_memory.json.migrated.bak")));
    }

    @Test
    void legacySameIdConfirmationConflictMustPreserveDatabaseConfirmationAndRemainUnmigrated()
            throws Exception {
        Path legacyDir = tempDir.resolve("same-id-confirmation-conflict");
        LongTermMemory existing = new LongTermMemory(legacyDir.toFile());
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant confirmedAt = Instant.parse("2026-09-25T00:00:00Z");
        assertTrue(existing.storeIfNovel(new MemoryEntry("a", "Java preference",
                MemoryEntry.MemoryType.FACT, createdAt,
                Map.of("scope", "global", "lastConfirmedAt", confirmedAt.toString()), 5)));
        MemoryEntry original = existing.retrieve("a").orElseThrow();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + legacyDir.resolve("memory.db").toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM memory_meta WHERE key='legacy_json_migration'");
        }
        Path legacyJson = legacyDir.resolve("long_term_memory.json");
        String legacyContent = new ObjectMapper().writeValueAsString(List.of(
                legacyEntry("a", "Java preference", Map.of("scope", "global",
                        "lastConfirmedAt", "2026-02-01T00:00:00Z"))));
        Files.writeString(legacyJson, legacyContent);

        assertThrows(IllegalStateException.class, () -> new LongTermMemory(legacyDir.toFile()));

        assertEquals(1, existing.size());
        MemoryEntry preserved = existing.retrieve("a").orElseThrow();
        assertEquals(original.getContent(), preserved.getContent());
        assertEquals(original.getMetadata(), preserved.getMetadata());
        assertEquals(original.getTimestamp(), preserved.getTimestamp());
        assertEquals(confirmedAt, LongTermMemory.lastConfirmedAtOf(preserved));
        assertEquals(5, existing.getTokenCount());
        assertNull(migrationMarker(legacyDir));
        assertTrue(Files.exists(legacyJson));
        assertEquals(legacyContent, Files.readString(legacyJson));
        assertFalse(Files.exists(legacyDir.resolve("long_term_memory.json.migrated.bak")));
    }

    private static Map<String, Object> legacyEntry(String id, String content,
                                                   Map<String, String> metadata) {
        return Map.of("id", id, "content", content, "type", "FACT",
                "timestamp", "2026-01-01T00:00:00Z", "metadata", metadata, "tokenCount", 5);
    }

    private static void assertMigrationReferencesResolve(LongTermMemory migrated) {
        for (MemoryEntry entry : migrated.getAll()) {
            for (String relation : List.of("supersedes", "supersededBy")) {
                String target = entry.getMetadata().get(relation);
                if (target != null) {
                    assertNotEquals(entry.getId(), target);
                    assertTrue(migrated.retrieve(target).isPresent(), entry.getId() + " -> " + target);
                }
            }
        }
    }

    private static String migrationMarker(Path storageDir) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + storageDir.resolve("memory.db").toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT value FROM memory_meta WHERE key='legacy_json_migration'")) {
            return result.next() ? result.getString(1) : null;
        }
    }

    @Test
    void supersedeCommittedByOneInstanceIsVisibleToAnother() {
        LongTermMemory first = new LongTermMemory(tempDir.toFile());
        LongTermMemory second = new LongTermMemory(tempDir.toFile());
        first.store(new MemoryEntry(
                "old-shared",
                "用户偏好 Java",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"),
                5
        ));

        assertTrue(second.supersede("old-shared", new MemoryEntry(
                "new-shared",
                "用户偏好 Python",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"),
                5
        )));

        assertEquals("superseded",
                LongTermMemory.statusOf(first.retrieve("old-shared").orElseThrow()));
        assertTrue(first.retrieve("new-shared").isPresent());
    }


}
