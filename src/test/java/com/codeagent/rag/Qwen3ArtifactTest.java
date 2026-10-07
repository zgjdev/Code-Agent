package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingInputPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "rag.qwen.artifact", matches = "true")
class Qwen3ArtifactTest {
    @TempDir Path cache;
    @Test void realOnnxTokenizationPoolingAndRepeatability() throws Exception {
        try (var provider = new Qwen3EvaluationProvider(Path.of("target/qwen-evaluation/model"), 1024,
                cache)) {
            var inputs = new EmbeddingInputPolicy();
            String query = inputs.prepareQuery("如何恢复中断任务");
            long[] tokens = provider.tokenIds(Qwen3EvaluationProvider.adapt(query));
            assertEquals(151643, tokens[tokens.length - 1], "fixed tokenizer must append EOS before pooling");
            System.out.println("Qwen sample token IDs=" + java.util.Arrays.toString(tokens));
            long started = System.nanoTime();
            float[] first = provider.embedAll(List.of(query)).get(0);
            float[] second = provider.embedAll(List.of(query)).get(0);
            assertEquals(1024, first.length);
            assertArrayEquals(first, second, 1e-6f);
            double norm = 0; for (float v : first) norm += v * (double) v;
            assertEquals(1, norm, 1e-5);
            float[] document = provider.embedAll(List.of(inputs.prepareDocument("class Plan { void resume() {} }"))).get(0);
            assertNotEquals(java.util.Arrays.toString(first), java.util.Arrays.toString(document));
            System.out.println("Qwen three short encodings ms=" + (System.nanoTime() - started) / 1_000_000);
            System.out.println(provider.statistics());
            assertEquals(3L, provider.statistics().get("nativeCalls"));
            provider.embedAll(List.of(inputs.prepareDocument("class Plan { void resume() {} }")));
            assertEquals(3L, provider.statistics().get("nativeCalls"));
            assertEquals(1L, provider.statistics().get("documentCacheHits"));
            provider.close();
            provider.close();
            assertThrows(com.codeagent.rag.embedding.EmbeddingException.class,
                    () -> provider.embedAll(List.of(query)));
            try (var smaller = new Qwen3EvaluationProvider(Path.of("target/qwen-evaluation/model"), 512, cache)) {
                float[] truncated = smaller.embedAll(List.of(inputs.prepareDocument("class Plan { void resume() {} }"))).get(0);
                assertEquals(0L, smaller.statistics().get("nativeCalls"));
                assertEquals(1L, smaller.statistics().get("documentCacheHits"));
                double prefixNorm = 0;
                for (int i = 0; i < 512; i++) prefixNorm += document[i] * (double) document[i];
                for (int i = 0; i < 512; i++) assertEquals(document[i] / Math.sqrt(prefixNorm), truncated[i], 1e-6);
                smaller.embedAll(List.of(query));
                smaller.embedAll(List.of(query));
                assertEquals(2L, smaller.statistics().get("nativeCalls"));
            }
        }
    }
}
