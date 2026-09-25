package com.codeagent.rag;

import com.codeagent.rag.stage.CodeRetrieverStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrieverStageIsolationTest {
    @Test
    void oneFailedStageDoesNotDiscardSuccessfulRankings(@TempDir Path root) {
        RetrievalContext context = new RetrievalContext(
                new RetrievalRequest(root, "query", 5, 1000, false, RetrievalIntent.CHUNKS),
                null, Optional.empty(), null);
        CodeRetrieverStage failed = stage(RetrievalSource.SEMANTIC_LOCAL, true);
        CodeRetrieverStage successful = stage(RetrievalSource.FTS_TERMS, false);

        RetrievalStageRunner.Result result = new RetrievalStageRunner().run(
                List.of(failed, successful), context);

        assertEquals(1, result.rankings().get(RetrievalSource.FTS_TERMS).size());
        assertTrue(result.rankings().get(RetrievalSource.SEMANTIC_LOCAL).isEmpty());
        assertTrue(result.degradedReasonCodes().contains("stage_semantic_local_failed"));
    }

    private static CodeRetrieverStage stage(RetrievalSource source, boolean fail) {
        return new CodeRetrieverStage() {
            @Override public RetrievalSource source() { return source; }
            @Override public List<RetrievalCandidate> retrieve(RetrievalContext context) throws Exception {
                if (fail) throw new Exception("private content must not enter diagnostics");
                return List.of(new RetrievalCandidate(1, "A.java", 1, 2, "method", "run",
                        "id", "code", 0, false, null, null));
            }
        };
    }
}
