package com.codeagent.memory;

import com.codeagent.rag.embedding.InProcessBgeEmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用仓库随 JAR 分发的真实本地 BGE 固化长期记忆语义阈值。
 */
class MemoryEmbeddingGoldenTest {

    @Test
    void bundledBgeSeparatesParaphraseFromUnrelatedQuery() throws Exception {
        String memory = "默认使用中文回答用户";
        String paraphrase = "后续都用汉语和我沟通";
        String unrelated = "修复 Maven 编译失败";

        try (InProcessBgeEmbeddingProvider provider = new InProcessBgeEmbeddingProvider()) {
            List<float[]> vectors = provider.embedAll(List.of(memory, paraphrase, unrelated));
            double relatedScore = MemoryRetriever.cosineSimilarity(vectors.get(0), vectors.get(1));
            double unrelatedScore = MemoryRetriever.cosineSimilarity(vectors.get(0), vectors.get(2));

            assertTrue(relatedScore >= MemoryRetriever.SEMANTIC_MIN_SCORE,
                    "related score=" + relatedScore);
            assertTrue(unrelatedScore < MemoryRetriever.SEMANTIC_MIN_SCORE,
                    "unrelated score=" + unrelatedScore);
            assertTrue(relatedScore > unrelatedScore,
                    "related=" + relatedScore + ", unrelated=" + unrelatedScore);
        }
    }
}
