package com.codeagent.memory;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.rag.embedding.InProcessQwen3EmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用显式预装的真实本地 Qwen FP32 模型验证长期记忆读取和写入候选阈值。
 */
@EnabledIfSystemProperty(named = "memory.qwen.artifact", matches = "true")
class MemoryEmbeddingGoldenTest {
    @TempDir Path directory;

    @Test
    void installedQwenSeparatesRelatedFromUnrelatedQueries() {
        List<GoldenCase> cases = List.of(
                new GoldenCase("Plan 状态使用 SQLite 持久化，支持任务节点恢复",
                        "之前的任务恢复机制把计划状态存在哪里？", true),
                new GoldenCase("默认使用中文回答用户", "后续都用汉语和我沟通", true),
                new GoldenCase("项目要求 Java 17", "这个仓库需要哪个 JDK 版本？", true),
                new GoldenCase("默认使用中文回答用户", "修复 Maven 编译失败", false),
                new GoldenCase("项目要求 Java 17", "解释 Plan DAG 的资源冲突检测", false));

        var config = new CodeAgentConfig.EmbeddingConfig();
        config.setLocalModelDirectory(InProcessQwen3EmbeddingProvider.defaultModelDirectory().toString());
        try (var cache = new MemoryEmbeddingCache(config, directory)) {
            List<Executable> assertions = new ArrayList<>();
            List<String> diagnostics = new ArrayList<>();
            for (int index = 0; index < cases.size(); index++) {
                GoldenCase goldenCase = cases.get(index);
                var entry = new MemoryEntry("golden-" + index, goldenCase.memory(),
                        MemoryEntry.MemoryType.FACT, Map.of("scope", "global"), 10);
                var query = cache.embedQuery(goldenCase.query()).orElseThrow();
                var document = cache.embeddingsFor(List.of(entry)).get(entry.getId());
                double score = MemoryRetriever.cosineSimilarity(
                        document, query);
                String message = "memory='" + goldenCase.memory() + "', query='"
                        + goldenCase.query() + "', score=" + score;
                diagnostics.add((goldenCase.related() ? "related=" : "unrelated=") + score);
                assertions.add(goldenCase.related()
                        ? () -> assertTrue(score >= MemoryRetriever.SEMANTIC_MIN_SCORE, message)
                        : () -> assertTrue(score < MemoryRetriever.SEMANTIC_MIN_SCORE, message));
                assertions.add(goldenCase.related()
                        ? () -> assertTrue(score >= MemoryRetriever.WRITE_CANDIDATE_MIN_SCORE, message)
                        : () -> assertTrue(score < MemoryRetriever.WRITE_CANDIDATE_MIN_SCORE, message));

                var memory = new LongTermMemory(directory.resolve("memory-" + index).toFile());
                memory.store(entry);
                // Same production cache and retrieval paths, including lexical fusion and time decay.
                var retriever = new MemoryRetriever(memory, cache, java.time.Clock.systemUTC());
                if (goldenCase.related()) {
                    assertions.add(() -> assertTrue(retriever.retrieveLongTerm(goldenCase.query(), 5).stream()
                            .anyMatch(found -> found.getId().equals(entry.getId())), message));
                    assertions.add(() -> assertTrue(retriever.retrieveWriteCandidates(goldenCase.query(), 5,
                            List.of(entry), MemoryEntry.MemoryType.FACT).contains(entry), message));
                } else {
                    assertions.add(() -> assertTrue(retriever.retrieveLongTerm(goldenCase.query(), 5).isEmpty(), message));
                    assertions.add(() -> assertTrue(retriever.retrieveWriteCandidates(goldenCase.query(), 5,
                            List.of(entry), MemoryEntry.MemoryType.FACT).isEmpty(), message));
                }
            }
            System.out.println("Qwen memory golden: " + String.join(", ", diagnostics));
            assertAll(String.join(", ", diagnostics), assertions);
        }
    }

    private record GoldenCase(String memory, String query, boolean related) {
    }
}
