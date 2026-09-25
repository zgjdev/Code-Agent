package com.codeagent.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalFusionTest {
    @Test
    void fusesIndependentSourcesDeterministicallyAndLimitsPerFile() {
        RetrievalCandidate consensus = candidate("A.java", 10, "run", "id-a");
        RetrievalCandidate boosted = candidate("B.java", 5, "Exact", "id-b");
        Map<RetrievalSource, List<RetrievalCandidate>> rankings = new LinkedHashMap<>();
        rankings.put(RetrievalSource.SYMBOL, List.of(boosted, consensus));
        rankings.put(RetrievalSource.FTS_TERMS, List.of(consensus));
        rankings.put(RetrievalSource.SEMANTIC_LOCAL, List.of(consensus));

        List<RetrievalHit> result = new RetrievalFusion().fuse(rankings, "Exact", 10);

        assertEquals("A.java", result.get(0).filePath(), "source consensus should beat one exact boost");
        assertEquals(3, result.get(0).sources().size());
        assertTrue(result.get(0).score() > result.get(1).score());
        for (int i = 0; i < 20; i++) {
            assertEquals(result, new RetrievalFusion().fuse(rankings, "Exact", 10));
        }
    }

    private static RetrievalCandidate candidate(String file, int line, String symbol, String id) {
        return new RetrievalCandidate(line, file, line, line + 2, "method", symbol, id,
                "void " + symbol + "() {}", 1, false, null, null);
    }
}
