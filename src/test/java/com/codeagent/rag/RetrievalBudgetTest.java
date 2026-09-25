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
}
