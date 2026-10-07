package com.codeagent.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalFusionTest {
    @Test void explicitClassAndMethodQueryUsesLexicalLane() {
        var lexical = fileCandidate("Exact.java", 1, "exact");
        var semantic = fileCandidate("Other.java", 1, "other");
        var rankings = Map.of(RetrievalSource.FTS_TERMS, List.of(lexical), RetrievalSource.SEMANTIC_LOCAL, List.of(semantic));
        assertEquals("Exact.java", new RetrievalFusion().fuse(rankings, "TaskStore restoreState", 10).get(0).filePath());
        assertEquals("Other.java", new RetrievalFusion().fuse(rankings, "restore interrupted tasks", 10).get(0).filePath());
    }
    @Test void typeBonusCannotReorderSemanticLane() {
        var first = fileCandidate("First.java", 1, "first");
        var second = candidate("Second.java", 2, "run", "second");
        var result = new RetrievalFusion().fuse(Map.of(RetrievalSource.SEMANTIC_LOCAL, List.of(first, second),
                RetrievalSource.FTS_TERMS, List.of(fileCandidate("Lex.java", 1, "lex"))), "如何恢复任务", 10);
        assertEquals("First.java", result.get(0).filePath());
        assertEquals("Second.java", result.get(1).filePath());
    }
    @Test
    void naturalLanguageProtectsSemanticOrderAndReservesLexicalSupplement() {
        var semantic = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> fileCandidate("S" + i + ".java", i, "s" + i)).toList();
        var lexical = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> fileCandidate("L" + i + ".java", i, "l" + i)).toList();
        var hits = new RetrievalFusion().fuse(Map.of(RetrievalSource.FTS_TERMS, lexical,
                RetrievalSource.SEMANTIC_LOCAL, semantic), "如何恢复中断的任务", 10);
        assertEquals(List.of("S1.java", "S2.java", "S3.java", "L1.java", "S4.java"),
                hits.stream().limit(5).map(RetrievalHit::filePath).toList());
    }

    @Test
    void weakLexicalConsensusCannotDisplaceLeadingSemanticEvidence() {
        var first = fileCandidate("First.java", 1, "first");
        var shared = fileCandidate("Shared.java", 2, "shared");
        var tail = java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(i -> fileCandidate("Tail" + i + ".java", i, "tail" + i)).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        tail.add(shared);
        tail.add(0, first);
        var hits = new RetrievalFusion().fuse(Map.of(RetrievalSource.FTS_TERMS, List.of(shared),
                RetrievalSource.SEMANTIC_LOCAL, tail), "哪个组件恢复执行状态", 10);
        assertEquals("First.java", hits.get(0).filePath());
        assertEquals("Shared.java", hits.get(3).filePath());
        assertEquals(2, hits.get(3).sources().size());
    }

    @Test
    void standaloneIdentifierKeepsLexicalFirstEvenWithManySemanticResults() {
        var lexical = fileCandidate("Exact.java", 1, "exact");
        var semantic = fileCandidate("Other.java", 1, "other");
        var hits = new RetrievalFusion().fuse(Map.of(RetrievalSource.FTS_TERMS, List.of(lexical),
                RetrievalSource.SEMANTIC_LOCAL, List.of(semantic)), "resumePendingTasks()", 10);
        assertEquals("Exact.java", hits.get(0).filePath());
    }

    @Test
    void fusesIndependentSourcesDeterministicallyAndLimitsPerFile() {
        RetrievalCandidate consensus = candidate("A.java", 10, "run", "id-a");
        RetrievalCandidate semanticOnly = candidate("B.java", 5, "Exact", "id-b");
        Map<RetrievalSource, List<RetrievalCandidate>> rankings = new LinkedHashMap<>();
        rankings.put(RetrievalSource.FTS_TERMS, List.of(consensus));
        rankings.put(RetrievalSource.SEMANTIC_LOCAL, List.of(consensus, semanticOnly));

        List<RetrievalHit> result = new RetrievalFusion().fuse(rankings, "Exact", 10);

        assertEquals("A.java", result.get(0).filePath(), "source consensus should beat one exact boost");
        assertEquals(2, result.get(0).sources().size());
        assertEquals(java.util.Set.of(RetrievalSource.SEMANTIC_LOCAL), result.get(1).sources());
        assertTrue(result.get(0).score() > result.get(1).score());
        for (int i = 0; i < 20; i++) {
            assertEquals(result, new RetrievalFusion().fuse(rankings, "Exact", 10));
        }
    }

    @Test
    void appliesConfiguredSourceWeights() {
        RetrievalCandidate lexical = fileCandidate("Lexical.java", 1, "lexical");
        RetrievalCandidate semantic = fileCandidate("Semantic.java", 1, "semantic");
        Map<RetrievalSource, List<RetrievalCandidate>> rankings = new LinkedHashMap<>();
        rankings.put(RetrievalSource.FTS_TERMS, List.of(lexical));
        rankings.put(RetrievalSource.SEMANTIC_LOCAL, List.of(semantic));

        Map<String, Double> scores = new RetrievalFusion().fuse(rankings, "query", 10).stream()
                .collect(java.util.stream.Collectors.toMap(RetrievalHit::filePath, RetrievalHit::score));

        assertEquals(1.2 / 61.0, scores.get("Lexical.java"), 0.000_000_1);
        assertEquals(1.0 / 61.0, scores.get("Semantic.java"), 0.000_000_1);
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
