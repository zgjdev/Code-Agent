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
        RetrievalCandidate graphOnly = candidate("B.java", 5, "Exact", "id-b");
        Map<RetrievalSource, List<RetrievalCandidate>> rankings = new LinkedHashMap<>();
        rankings.put(RetrievalSource.FTS_TERMS, List.of(consensus));
        rankings.put(RetrievalSource.SEMANTIC_LOCAL, List.of(consensus));
        rankings.put(RetrievalSource.GRAPH, List.of(graphOnly));

        List<RetrievalHit> result = new RetrievalFusion().fuse(rankings, "Exact", 10);

        assertEquals("A.java", result.get(0).filePath(), "source consensus should beat one exact boost");
        assertEquals(2, result.get(0).sources().size());
        assertEquals(java.util.Set.of(RetrievalSource.GRAPH), result.get(1).sources());
        assertTrue(result.get(0).score() > result.get(1).score());
        for (int i = 0; i < 20; i++) {
            assertEquals(result, new RetrievalFusion().fuse(rankings, "Exact", 10));
        }
    }

    @Test
    void appliesConfiguredSourceWeights() {
        RetrievalCandidate lexical = fileCandidate("Lexical.java", 1, "lexical");
        RetrievalCandidate semantic = fileCandidate("Semantic.java", 1, "semantic");
        RetrievalCandidate graph = fileCandidate("Graph.java", 1, "graph");
        Map<RetrievalSource, List<RetrievalCandidate>> rankings = new LinkedHashMap<>();
        rankings.put(RetrievalSource.FTS_TERMS, List.of(lexical));
        rankings.put(RetrievalSource.SEMANTIC_LOCAL, List.of(semantic));
        rankings.put(RetrievalSource.GRAPH, List.of(graph));

        Map<String, Double> scores = new RetrievalFusion().fuse(rankings, "query", 10).stream()
                .collect(java.util.stream.Collectors.toMap(RetrievalHit::filePath, RetrievalHit::score));

        assertEquals(1.2 / 61.0, scores.get("Lexical.java"), 0.000_000_1);
        assertEquals(1.0 / 61.0, scores.get("Semantic.java"), 0.000_000_1);
        assertEquals(0.8 / 61.0, scores.get("Graph.java"), 0.000_000_1);
    }

    @Test
    void limitsFusedResultsToThreeHitsPerFile() {
        List<RetrievalCandidate> sameFile = java.util.stream.IntStream.rangeClosed(1, 4)
                .mapToObj(line -> fileCandidate("A.java", line, "id-" + line))
                .toList();

        List<RetrievalHit> result = new RetrievalFusion().fuse(
                Map.of(RetrievalSource.FTS_TERMS, sameFile), "query", 10);

        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(hit -> hit.filePath().equals("A.java")));
    }

    private static RetrievalCandidate candidate(String file, int line, String symbol, String id) {
        return new RetrievalCandidate(line, file, line, line + 2, "method", symbol, id,
                "void " + symbol + "() {}", 1, false, null, null);
    }

    private static RetrievalCandidate fileCandidate(String file, int line, String id) {
        return new RetrievalCandidate(line, file, line, line, "file", id, id,
                id, 1, false, null, null);
    }
}
