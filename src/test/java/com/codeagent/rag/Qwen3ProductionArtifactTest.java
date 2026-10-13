package com.codeagent.rag;

import com.codeagent.rag.embedding.*;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="rag.qwen.production.artifact", matches="true")
class Qwen3ProductionArtifactTest {
    @TempDir Path directory;
    @Test void defaultToolRegistryUsesQwenWithoutExplicitReconfiguration() throws Exception {
        String previous = System.getProperty("codeagent.rag.dir");
        System.setProperty("codeagent.rag.dir", directory.resolve("index").toString());
        var registry = new ToolRegistry(); registry.setProjectPath(directory.toString());
        try (var service = registry.getCodeRetrievalService()) {
            var response = service.search(new RetrievalRequest(directory, "如何恢复中断任务", 5, 1000, false, RetrievalIntent.CHUNKS));
            assertEquals("local-qwen3", response.diagnostics().embeddingProviderId());
            assertTrue(response.diagnostics().degradedReasonCodes().isEmpty());
        } finally {
            if (previous == null) System.clearProperty("codeagent.rag.dir"); else System.setProperty("codeagent.rag.dir", previous);
        }
    }
    @Test void productionOutputMatchesPinnedFp32Experiment() throws Exception {
        var inputs = new EmbeddingInputPolicy();
        var batch = List.of(inputs.prepareQuery("如何恢复中断任务"), inputs.prepareDocument("class Plan { void resumePendingTasks() { restorePlanState(); } }"));
        List<float[]> actual;
        try (var provider = new InProcessQwen3EmbeddingProvider(InProcessQwen3EmbeddingProvider.defaultModelDirectory())) {
            actual = provider.embedAll(batch);
            assertArrayEquals(actual.get(0), provider.embedAll(List.of(batch.get(0))).get(0), 1e-6f);
        }
        try (var reference = new Qwen3EvaluationProvider(Path.of("target/qwen-evaluation/model"), 1024, directory.resolve("cache"), true)) {
            var expected = reference.embedAll(batch);
            for (int i = 0; i < batch.size(); i++) assertArrayEquals(expected.get(i), actual.get(i), 1e-6f);
        }
    }
}
