package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingInputPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Quantization check against the same export in FP32; does not claim PyTorch/ONNX parity. */
@EnabledIfSystemProperty(named = "rag.qwen.parity", matches = "true")
class Qwen3QuantizationParityTest {
    @Test void comparesInt8ToPinnedFp32Export() throws Exception {
        Path model = Path.of("target/qwen-evaluation/model"), cache = Path.of("target/qwen-evaluation/vector-cache");
        var policy = new EmbeddingInputPolicy();
        List<String> inputs = new ArrayList<>(List.of(policy.prepareQuery("如何恢复中断任务"),
                policy.prepareQuery("如何用新事实替换长期记忆"),
                policy.prepareDocument("class Plan { void resumePendingTasks() { restorePlanState(); } }"),
                policy.prepareDocument("class Memory { void supersede() { repository.markSuperseded(); } }")));
        String production = Files.readString(Path.of("src/main/java/com/codeagent/rag/embedding/EmbeddingInputPolicy.java"));
        var parts = policy.prepareDocumentParts(production);
        assertTrue(parts.size() > 1, "also compare realistic numbered document parts");
        inputs.add(parts.get(0));
        inputs.add(parts.get(parts.size() - 1));
        List<float[]> full, quantized;
        long[] referenceTokens;
        try (var fp32 = new Qwen3EvaluationProvider(model, 1024, cache, true)) {
            full = fp32.embedAll(inputs);
            referenceTokens = fp32.tokenIds(Qwen3EvaluationProvider.adapt(inputs.get(0)));
        }
        try (var int8 = new Qwen3EvaluationProvider(model, 1024, cache)) {
            quantized = int8.embedAll(inputs);
            assertArrayEquals(referenceTokens, int8.tokenIds(Qwen3EvaluationProvider.adapt(inputs.get(0))));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            double cosine = dot(full.get(i), quantized.get(i)), maxError = 0;
            for (int j = 0; j < 1024; j++) maxError = Math.max(maxError, Math.abs(full.get(i)[j] - quantized.get(i)[j]));
            rows.add(Map.of("sample", i, "cosineFp32Int8", cosine, "maxAbsoluteError", maxError));
        }
        Map<String, Object> report = Map.of("reference", "same fixed ONNX export FP32, not independent PyTorch reference",
                "revision", Qwen3EvaluationProvider.REVISION, "samples", rows,
                "fp32QueryDocumentCosine", List.of(dot(full.get(0), full.get(2)), dot(full.get(1), full.get(3))),
                "int8QueryDocumentCosine", List.of(dot(quantized.get(0), quantized.get(2)), dot(quantized.get(1), quantized.get(3))),
                "referenceTokenIds", referenceTokens);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/qwen-evaluation/parity.json").toFile(), report);
        System.out.println(report);
        for (var row : rows) assertTrue((double) row.get("cosineFp32Int8") > .95, "quantization cosine drift: " + row);
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0; for (int i = 0; i < a.length; i++) sum += a[i] * (double) b[i]; return sum;
    }
}
