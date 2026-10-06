package com.codeagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalBudgetTest {
    @Test
    void appliesTopKAndCharacterBudgetWithoutSplittingNormalLines() {
        RetrievalHit hit = new RetrievalHit("A.java", 1, 3, "method", "run",
                "line one\nline two\nline three", 1, Set.of(RetrievalSource.FTS_TERMS));
        RetrievalBudget.Result result = new RetrievalBudget().apply(List.of(hit), 5, 18);

        assertEquals(1, result.hits().size());
        assertEquals("line one\nline two", result.hits().get(0).content());
        assertTrue(result.partial());
    }

    @Test
    void marksPartialWhenTopKTruncatesHits() {
        RetrievalHit first = hit("A.java", 1);
        RetrievalHit second = hit("B.java", 2);

        RetrievalBudget.Result result = new RetrievalBudget().apply(
                List.of(first, second), 1, 1_000);

        assertEquals(List.of(first), result.hits());
        assertTrue(result.partial());
    }

    private static RetrievalHit hit(String file, int line) {
        return new RetrievalHit(file, line, line, "file", file, "content", 1,
                Set.of(RetrievalSource.FTS_TERMS));
    }

    @Test
    void skipsEmptyContentWithoutBlockingUsefulHits() {
        RetrievalHit empty = new RetrievalHit("Empty.java", 1, 1, "file", "empty", "", 2,
                Set.of(RetrievalSource.FTS_TERMS));
        RetrievalHit useful = hit("Useful.java", 1);
        var result = new RetrievalBudget().apply(List.of(empty, useful), 5, 1_000);
        assertEquals(List.of(useful), result.hits());
        assertTrue(result.partial());
    }

    @Test
    void clipsLongSingleLineAfterAnEarlierHitWithinRemainingBudget() {
        RetrievalHit first = hit("A.java", 1);
        RetrievalHit longLine = new RetrievalHit("B.java", 1, 1, "file", "b", "0123456789", 1,
                Set.of(RetrievalSource.FTS_TERMS));
        var result = new RetrievalBudget().apply(List.of(first, longLine), 5, 10);
        assertEquals(2, result.hits().size());
        assertEquals("012", result.hits().get(1).content());
        assertEquals(10, result.hits().stream().mapToInt(h -> h.content().length()).sum());
        assertTrue(result.partial());
    }
}
