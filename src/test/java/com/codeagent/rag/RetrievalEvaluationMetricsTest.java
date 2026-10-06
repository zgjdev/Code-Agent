package com.codeagent.rag;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalEvaluationMetricsTest {
    private final List<RetrievalEvaluationMetrics.Evidence> truth = List.of(
            new RetrievalEvaluationMetrics.Evidence("A.java", "first()"),
            new RetrievalEvaluationMetrics.Evidence("B.java", "second()"));

    @Test void measuresRanksEvidenceCoverageAndShortListPrecision() {
        var result = RetrievalEvaluationMetrics.measure(truth,
                List.of(hit("X.java", "noise"), hit("A.java", "first()"),
                        hit("A.java", "first()"), hit("B.java", "second()")), 5);
        assertEquals(0.6, result.precision(), 1e-9);
        assertEquals(1.0, result.recall());
        assertEquals(0.5, result.reciprocalRank());
        assertEquals((1 / log2(3) + 1 / log2(5)) / 2, result.discountedEvidenceCoverage(), 1e-9);
        assertTrue(result.hit());
        assertTrue(result.complete());
    }

    @Test void requiresReturnedEvidenceNotFileOrMetadataRange() {
        var result = RetrievalEvaluationMetrics.measure(truth,
                List.of(hit("A.java", "class A {}"), hit("wrong.java", "first()")), 5);
        assertEquals(0, result.recall());
        assertEquals(0, result.precision());
        assertFalse(result.hit());
    }

    @Test void cutsOffRanksAndDoesNotCountDuplicateEvidenceTwice() {
        var result = RetrievalEvaluationMetrics.measure(truth,
                List.of(hit("A.java", "first()"), hit("A.java", "first()"),
                        hit("B.java", "second()")), 2);
        assertEquals(0.5, result.recall());
        assertEquals(0.5, result.discountedEvidenceCoverage(), 1e-9);
        assertFalse(result.complete());
    }

    @Test void handlesEmptyResultsAndNoAnswerSeparately() {
        assertEquals(0, RetrievalEvaluationMetrics.measure(truth, List.of(), 5).discountedEvidenceCoverage());
        assertFalse(RetrievalEvaluationMetrics.measure(List.of(), List.of(), 5).falseReturn());
        assertTrue(RetrievalEvaluationMetrics.measure(List.of(),
                List.of(hit("A.java", "anything")), 5).falseReturn());
        assertThrows(IllegalArgumentException.class,
                () -> RetrievalEvaluationMetrics.measure(truth, List.of(), 0));
    }

    @Test void oneChunkCanCoverSeveralEvidenceUnitsWithBoundedDiscountedCoverage() {
        var sameFile = List.of(new RetrievalEvaluationMetrics.Evidence("A.java", "first()"),
                new RetrievalEvaluationMetrics.Evidence("A.java", "second()"));
        var result = RetrievalEvaluationMetrics.measure(sameFile,
                List.of(hit("A.java", "first() second()")), 5);
        assertEquals(1, result.recall());
        assertEquals(1, result.discountedEvidenceCoverage());
        assertEquals(0.2, result.precision());
    }

    @Test void signatureWithoutRequiredBodyIsNotImplementationEvidence() {
        var evidence = List.of(new RetrievalEvaluationMetrics.Evidence("A.java", "first()",
                List.of("return actualResult;")));
        assertFalse(RetrievalEvaluationMetrics.measure(evidence,
                List.of(hit("A.java", "void first() {")), 5).hit());
        assertTrue(RetrievalEvaluationMetrics.measure(evidence,
                List.of(hit("A.java", "void first() { return actualResult; }")), 5).hit());
    }

    private static RetrievalHit hit(String path, String content) {
        return new RetrievalHit(path, 1, 9999, "method", "symbol", content, 1, Set.of());
    }
    private static double log2(double x) { return Math.log(x) / Math.log(2); }
}
