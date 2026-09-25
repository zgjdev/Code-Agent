package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingResolution;
import com.codeagent.rag.embedding.InProcessBgeEmbeddingProvider;
import com.codeagent.search.JavaCodeSearchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalQualityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void reportsThirtyCaseLexicalAndLocalSemanticMetrics(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        writeCorpus(root);
        List<GoldenCase> cases;
        try (InputStream input = getClass().getResourceAsStream("/rag/retrieval-golden-set.json")) {
            assertNotNull(input);
            cases = MAPPER.readValue(input, new TypeReference<>() {});
        }
        assertTrue(cases.size() >= 30);
        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(temp.resolve("v2.db"));
             InProcessBgeEmbeddingProvider provider = new InProcessBgeEmbeddingProvider()) {
            DefaultCodeRetrievalService service = new DefaultCodeRetrievalService(index,
                    new JavaCodeSearchService(Set.of("target")),
                    new EmbeddingResolution(Optional.empty(), "off", false));
            service.refresh(new IndexRefreshRequest(root, false));
            Metrics lexical = evaluate(service, root, cases);

            service.reconfigureEmbedding(new EmbeddingResolution(Optional.of(provider), "local", false));
            service.refresh(new IndexRefreshRequest(root, false));
            Metrics semantic = evaluate(service, root, cases);

            System.out.println("RAG quality lexical=" + lexical + " lexical+local-semantic=" + semantic);
            assertEquals(lexical.exactTop1, semantic.exactTop1,
                    "semantic must not regress exact identifier Top-1");
            assertTrue(semantic.recallAt5 >= lexical.recallAt5 - 0.10);
            service.close();
        }
    }

    private static Metrics evaluate(DefaultCodeRetrievalService service, Path root,
                                    List<GoldenCase> cases) {
        int found = 0;
        int exactTotal = 0;
        int exactTop1 = 0;
        double reciprocalRanks = 0;
        int empty = 0;
        List<Long> latencies = new ArrayList<>();
        for (GoldenCase testCase : cases) {
            long start = System.nanoTime();
            RetrievalResponse response = service.search(new RetrievalRequest(root, testCase.query,
                    5, 8_000, false, RetrievalIntent.CHUNKS));
            latencies.add((System.nanoTime() - start) / 1_000_000);
            if (response.hits().isEmpty()) empty++;
            int rank = 0;
            for (int i = 0; i < response.hits().size(); i++) {
                if (response.hits().get(i).filePath().equals(testCase.expectedPath)) { rank = i + 1; break; }
            }
            if (rank > 0) { found++; reciprocalRanks += 1.0 / rank; }
            if (testCase.exact) {
                exactTotal++;
                if (rank == 1) exactTop1++;
            }
        }
        latencies.sort(Comparator.naturalOrder());
        long p95 = latencies.get(Math.min(latencies.size() - 1,
                (int) Math.ceil(latencies.size() * 0.95) - 1));
        return new Metrics(found / (double) cases.size(), reciprocalRanks / cases.size(),
                exactTop1, exactTotal, empty / (double) cases.size(), p95);
    }

    private static void writeCorpus(Path root) throws Exception {
        Files.writeString(root.resolve("ContextManager.java"), "class ContextManager {\n"
                + " // 压缩对话上下文，保留最近用户消息并生成对话摘要\n void compactHistory() {}\n}");
        Files.writeString(root.resolve("ModeRouter.java"), "class ModeRouter {\n"
                + " // 选择执行模式，判断计划还是反应模式并路由顶层任务\n void selectMode() {}\n}");
        Files.writeString(root.resolve("ToolRegistry.java"), "class ToolRegistry {\n"
                + " // 并发执行工具调用，保持工具结果原始顺序，负责工具注册和调度\n void executeTools() {}\n}");
        Files.writeString(root.resolve("SessionStore.java"), "class SessionStore {\n"
                + " // 追加保存会话事件，持久化不可变原始会话日志到磁盘\n void appendEvent() {}\n}");
        Files.writeString(root.resolve("IndexCoordinator.java"), "class IndexCoordinator {\n"
                + " // 增量刷新代码索引，删除消失文件，词法事务后补齐缺失向量\n void refreshIndex() {}\n}");
    }

    private record GoldenCase(String id, String query, String expectedPath, boolean exact) {}
    private record Metrics(double recallAt5, double mrr, int exactTop1,
                           int exactTotal, double noResultRate, long p95Millis) {}
}
