package com.codeagent.memory;

import com.codeagent.llm.GLMClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {
    @TempDir Path tempDir;

    @Test
    void shouldReportConversationHistoryAsTheOnlyShortTermContext() {
        MemoryManager manager = new MemoryManager(new GLMClient("test-key"), 40, 128000,
                new LongTermMemory(tempDir.toFile()));
        assertTrue(manager.getSystemStatus().contains("由当前 Agent conversationHistory 维护"));
    }

    @Test
    void shouldClearLongTermMemoryOnlyWhenExplicitlyRequested() {
        LongTermMemory longTerm = new LongTermMemory(tempDir.toFile());
        MemoryManager manager = new MemoryManager(new GLMClient("test-key"), 32768, 128000, longTerm);
        longTerm.store(new MemoryEntry("f1", "用户偏好使用中文交流",
                MemoryEntry.MemoryType.FACT, java.util.Map.of("scope", "global"), 5));
        longTerm.store(new MemoryEntry("f2", "项目路径: /tmp/demo",
                MemoryEntry.MemoryType.FACT, java.util.Map.of("scope", "global"), 5));
        assertEquals(2, longTerm.size());
        manager.clearLongTerm();
        assertEquals(0, longTerm.size());
    }

    @Test
    void shouldStoreProjectScopedFactsByDefault() {
        LongTermMemory longTerm = new LongTermMemory(tempDir.toFile());
        MemoryManager manager = new MemoryManager(new GLMClient("test-key"), 32768, 128000, longTerm);
        manager.setProjectPath("/repo/current");
        manager.storeFact("当前项目使用 Java 17");
        manager.storeFact("默认用中文回答", "global");
        MemoryEntry projectEntry = longTerm.search("Java", 5, manager.getCurrentProject()).get(0);
        assertEquals("project", projectEntry.getMetadata().get("scope"));
        assertTrue(Path.of(projectEntry.getMetadata().get("project"))
                .endsWith(Path.of("repo", "current")));
        assertEquals("global", longTerm.search("中文", 5).get(0).getMetadata().get("scope"));
    }

    @Test
    void shouldSearchOnlyCurrentProjectAndGlobalFacts() {
        LongTermMemory longTerm = new LongTermMemory(tempDir.toFile());
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider().fail(true);
        MemoryRetriever retriever = new MemoryRetriever(
                longTerm, new MemoryEmbeddingCache(provider), java.time.Clock.systemUTC());
        MemoryManager manager = new MemoryManager(
                new MemoryTestLlmClient("{\"action\":\"create\"}"),
                com.codeagent.context.ContextProfile.custom(128000),
                longTerm,
                retriever,
                new MemoryRelationClassifier(new MemoryTestLlmClient("{\"action\":\"create\"}")),
                "/repo/current");
        longTerm.store(new MemoryEntry("current", "当前项目使用 Java 17", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "project", "project", manager.getCurrentProject()), 10));
        longTerm.store(new MemoryEntry("other", "其他项目使用 Java 8", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "project", "project", "/repo/other"), 10));
        List<MemoryEntry> results = manager.searchLongTerm("Java", 10);
        assertEquals(1, results.size());
        assertEquals("current", results.get(0).getId());
    }

    @Test
    void explicitUpdateCanSupersedeExistingMemory() {
        LongTermMemory longTerm = new LongTermMemory(tempDir.toFile());
        String oldText = "用户偏好使用 Java";
        String incoming = "用户偏好使用 Python";
        longTerm.store(new MemoryEntry("old", oldText, MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "global"), 5));

        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector(oldText, 1f, 0f)
                .vector(incoming, 1f, 0f);
        MemoryRetriever retriever = new MemoryRetriever(
                longTerm, new MemoryEmbeddingCache(provider), java.time.Clock.systemUTC());
        MemoryTestLlmClient llm = new MemoryTestLlmClient(
                "{\"action\":\"supersede\",\"targetId\":\"old\","
                        + "\"evidence\":\"我现在更喜欢 Python\"}");
        MemoryManager manager = new MemoryManager(
                llm,
                com.codeagent.context.ContextProfile.custom(128000),
                longTerm,
                retriever,
                new MemoryRelationClassifier(llm),
                "/repo/current");

        String result = manager.storeFactWithResult(
                incoming,
                "global",
                "以后不要记我喜欢 Java 了，我现在更喜欢 Python");

        assertTrue(result.contains("已更新长期记忆"));
        assertEquals("superseded", LongTermMemory.statusOf(longTerm.retrieve("old").orElseThrow()));
        assertEquals(1, longTerm.getActiveVisible("/repo/current").size());
        assertEquals(incoming, longTerm.getActiveVisible("/repo/current").get(0).getContent());
    }

    @Test
    void duplicateWriteReportsConfirmationInsteadOfNoOp() {
        LongTermMemory longTerm = new LongTermMemory(tempDir.toFile());
        MemoryManager manager = new MemoryManager(
                new MemoryTestLlmClient("{\"action\":\"create\"}"),
                32768,
                128000,
                longTerm);
        manager.setProjectPath("/repo/current");

        manager.storeFactWithResult(
                "默认使用中文回答",
                "global",
                "记住，默认使用中文回答");
        String duplicate = manager.storeFactWithResult(
                "默认使用中文回答",
                "global",
                "再记一下，默认使用中文回答");

        assertTrue(duplicate.contains("已确认已有长期记忆"));
        assertEquals(1, longTerm.size());
        assertTrue(longTerm.retrieve(longTerm.getAll().get(0).getId())
                .orElseThrow().getMetadata().containsKey("lastConfirmedAt"));
    }

    @Test
    void compressionTriggerRatioAppliesToAllModelsUniformly() {
        MemoryManager manager = new MemoryManager(new GLMClient("test-key"));
        assertEquals(0.835, manager.getContextProfile().compressionTriggerRatio(), 0.001);
        assertEquals(200000, manager.getTokenBudget().getContextWindow());
        assertEquals(167000, manager.getContextProfile().compressionTriggerTokens());
    }
}
