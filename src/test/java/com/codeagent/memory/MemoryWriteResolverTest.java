package com.codeagent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryWriteResolverTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path tempDir;

    @Test
    void exactEquivalentUsesDeterministicFastPathWithoutLlm() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile());
        memory.store(new MemoryEntry("old", "用户偏好使用 Ｊａｖａ。",
                MemoryEntry.MemoryType.FACT, Map.of("scope", "global"), 5));
        MemoryTestLlmClient client = new MemoryTestLlmClient("{\"action\":\"create\"}");
        MemoryWriteResolver resolver = resolver(memory,
                new MemoryTestEmbeddingProvider().fail(true), client);

        var result = resolver.resolveAndStore(
                "用户偏好使用 java", "global", "/repo/current", "记住这个偏好");

        assertEquals(MemoryWriteResolver.Action.DUPLICATE, result.action());
        assertEquals(0, client.calls());
        assertEquals(1, memory.size());
    }

    @Test
    void semanticParaphraseCanBeClassifiedAsDuplicate() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile());
        String oldText = "用户偏好使用 Java";
        String incoming = "Java 是用户首选的开发语言";
        memory.store(new MemoryEntry("old", oldText,
                MemoryEntry.MemoryType.FACT, Map.of("scope", "global"), 5));
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector(oldText, 1f, 0f)
                .vector(incoming, 1f, 0f);
        MemoryTestLlmClient client = new MemoryTestLlmClient(
                "{\"action\":\"duplicate\",\"targetId\":\"old\"}");
        MemoryWriteResolver resolver = resolver(memory, provider, client);

        var result = resolver.resolveAndStore(
                incoming, "global", "/repo/current", "记住，Java 是我首选的开发语言");

        assertEquals(MemoryWriteResolver.Action.DUPLICATE, result.action());
        assertEquals(1, client.calls());
        assertEquals(1, memory.size());
    }

    @Test
    void explicitPreferenceChangeSupersedesOldMemory() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile());
        String oldText = "用户偏好使用 Java";
        String incoming = "用户偏好使用 Python";
        memory.store(new MemoryEntry("old", oldText,
                MemoryEntry.MemoryType.FACT, Map.of("scope", "global"), 5));
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector(oldText, 1f, 0f)
                .vector(incoming, 1f, 0f);
        MemoryTestLlmClient client = new MemoryTestLlmClient(
                "{\"action\":\"supersede\",\"targetId\":\"old\","
                        + "\"evidence\":\"我现在更喜欢 Python\"}");
        MemoryWriteResolver resolver = resolver(memory, provider, client);

        var result = resolver.resolveAndStore(
                incoming,
                "global",
                "/repo/current",
                "以后不要记我喜欢 Java 了，我现在更喜欢 Python");

        assertEquals(MemoryWriteResolver.Action.SUPERSEDED, result.action());
        assertEquals("superseded", LongTermMemory.statusOf(memory.retrieve("old").orElseThrow()));
        assertEquals(1, memory.getActiveVisible("/repo/current").size());
        assertEquals("用户偏好使用 Python",
                memory.getActiveVisible("/repo/current").get(0).getContent());
        assertEquals("old",
                memory.getActiveVisible("/repo/current").get(0).getMetadata().get("supersedes"));
    }

    @Test
    void complementaryPreferenceCreatesNewActiveMemory() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile());
        String oldText = "用户偏好使用 Java";
        String incoming = "用户也喜欢使用 Python";
        memory.store(new MemoryEntry("old", oldText,
                MemoryEntry.MemoryType.FACT, Map.of("scope", "global"), 5));
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider()
                .vector(oldText, 1f, 0f)
                .vector(incoming, 1f, 0f);
        MemoryTestLlmClient client = new MemoryTestLlmClient("{\"action\":\"create\"}");
        MemoryWriteResolver resolver = resolver(memory, provider, client);

        var result = resolver.resolveAndStore(
                incoming, "global", "/repo/current", "记住，我也喜欢 Python");

        assertEquals(MemoryWriteResolver.Action.CREATED, result.action());
        assertEquals(2, memory.getActiveVisible("/repo/current").size());
    }

    @Test
    void projectDomainIsResolvedBeforeEmbeddingCandidates() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile());
        memory.store(new MemoryEntry("project-a", "项目使用 Java 17",
                MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/a"), 5));
        MemoryTestEmbeddingProvider provider = new MemoryTestEmbeddingProvider().fail(true);
        MemoryTestLlmClient client = new MemoryTestLlmClient(
                "{\"action\":\"supersede\",\"targetId\":\"project-a\",\"evidence\":\"改成\"}");
        MemoryWriteResolver resolver = resolver(memory, provider, client);

        var result = resolver.resolveAndStore(
                "项目使用 Java 21", "project", "/repo/b", "记住，这个项目改成 Java 21");

        assertEquals(MemoryWriteResolver.Action.CREATED, result.action());
        assertEquals(0, client.calls());
        assertEquals("active", LongTermMemory.statusOf(memory.retrieve("project-a").orElseThrow()));
    }

    private static MemoryWriteResolver resolver(LongTermMemory memory,
                                                MemoryTestEmbeddingProvider provider,
                                                MemoryTestLlmClient client) {
        MemoryRetriever retriever = new MemoryRetriever(
                memory, new MemoryEmbeddingCache(provider), CLOCK);
        return new MemoryWriteResolver(
                memory, retriever, new MemoryRelationClassifier(client));
    }
}
