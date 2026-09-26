package com.codeagent.memory;

import com.codeagent.rag.embedding.InProcessBgeEmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用仓库随 JAR 分发的真实本地 BGE 固化长期记忆语义阈值。
 */
class MemoryEmbeddingGoldenTest {

    @Test
    void bundledBgeSeparatesRelatedFromUnrelatedQueries() throws Exception {
        List<GoldenCase> cases = List.of(
                new GoldenCase("Plan 状态使用 SQLite 持久化，支持任务节点恢复",
                        "之前的任务恢复机制把计划状态存在哪里？", true),
                new GoldenCase("默认使用中文回答用户", "后续都用汉语和我沟通", true),
                new GoldenCase("项目要求 Java 17", "这个仓库需要哪个 JDK 版本？", true),
                new GoldenCase("默认使用中文回答用户", "修复 Maven 编译失败", false),
                new GoldenCase("项目要求 Java 17", "解释 Plan DAG 的资源冲突检测", false));

        try (InProcessBgeEmbeddingProvider provider = new InProcessBgeEmbeddingProvider()) {
            List<String> inputs = cases.stream()
                    .flatMap(goldenCase -> List.of(goldenCase.memory(), goldenCase.query()).stream())
                    .toList();
            List<float[]> vectors = provider.embedAll(inputs);
            List<Executable> assertions = new ArrayList<>();
            List<String> diagnostics = new ArrayList<>();
            for (int index = 0; index < cases.size(); index++) {
                GoldenCase goldenCase = cases.get(index);
                double score = MemoryRetriever.cosineSimilarity(
                        vectors.get(index * 2), vectors.get(index * 2 + 1));
                String message = "memory='" + goldenCase.memory() + "', query='"
                        + goldenCase.query() + "', score=" + score;
                diagnostics.add((goldenCase.related() ? "related=" : "unrelated=") + score);
                assertions.add(goldenCase.related()
                        ? () -> assertTrue(score >= MemoryRetriever.SEMANTIC_MIN_SCORE, message)
                        : () -> assertTrue(score < MemoryRetriever.SEMANTIC_MIN_SCORE, message));
            }
            assertAll(String.join(", ", diagnostics), assertions);
        }
    }

    private record GoldenCase(String memory, String query, boolean related) {
    }
}
