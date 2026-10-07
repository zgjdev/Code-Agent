package com.codeagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class Qwen3EvaluationProviderTest {
    @Test void checksArtifactDigestBeforeLoadingNativeModel(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("model.onnx");
        Files.writeString(file, "corrupt artifact");
        assertThrows(IllegalArgumentException.class,
                () -> Qwen3EvaluationProvider.requireHash(file, Qwen3EvaluationProvider.MODEL_SHA));
        Qwen3EvaluationProvider.requireHash(file, Qwen3EvaluationProvider.hash(Files.readAllBytes(file)));
    }
    @Test void adaptsQueryAndDocumentWithoutChangingChunkParts() {
        assertEquals("Instruct: " + Qwen3EvaluationProvider.INSTRUCTION + "\nQuery: 如何恢复任务",
                Qwen3EvaluationProvider.adapt("为这个句子生成表示以用于检索相关文章：如何恢复任务"));
        assertEquals("片段 2/3\nclass Plan {}",
                Qwen3EvaluationProvider.adapt("代码文档：片段 2/3\nclass Plan {}"));
        assertThrows(IllegalArgumentException.class, () -> Qwen3EvaluationProvider.adapt("unknown prefix"));
    }

    @Test void poolsLastUnpaddedTokenAndNormalizesAfterDimensionSelection() {
        float[][] hidden = {{1, 0, 0}, {3, 4, 12}, {100, 100, 100}};
        assertArrayEquals(new float[]{.6f, .8f},
                Qwen3EvaluationProvider.pool(hidden, new long[]{1, 1, 0}, 2), 1e-6f);
        float unit = (float) (1 / Math.sqrt(3));
        assertArrayEquals(new float[]{unit, unit, unit},
                Qwen3EvaluationProvider.pool(hidden, new long[]{0, 1, 1}, 3), 1e-6f);
    }

    @Test void rejectsInvalidPoolingInsteadOfPersistingBadVectors() {
        assertThrows(IllegalArgumentException.class,
                () -> Qwen3EvaluationProvider.pool(new float[][]{{1, 2}}, new long[]{0}, 2));
        assertThrows(IllegalArgumentException.class,
                () -> Qwen3EvaluationProvider.pool(new float[][]{{0, 0}}, new long[]{1}, 2));
        assertThrows(IllegalArgumentException.class,
                () -> Qwen3EvaluationProvider.pool(new float[][]{{1, Float.NaN}}, new long[]{1}, 2));
        assertThrows(IllegalArgumentException.class,
                () -> Qwen3EvaluationProvider.pool(new float[][]{{1, 2}}, new long[]{1}, 3));
    }
}
