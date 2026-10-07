package com.codeagent.rag;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class RetrievalQueryAnalysisTest {
    @Test void extractsCodeAnchorsInsideNaturalLanguageWithoutRewritingIntent() {
        assertEquals(List.of("store.save()"), RetrievalQueryAnalysis.codeHints("看一下store.save()这个方法"));
        assertEquals(List.of("PlanStateStore", "restoreState"), RetrievalQueryAnalysis.codeHints("为什么 `PlanStateStore` 的 restoreState 没恢复任务？"));
        assertEquals(List.of("memory_db"), RetrievalQueryAnalysis.codeHints("memory_db 为什么没有更新？"));
        assertTrue(RetrievalQueryAnalysis.codeHints("how to recover unfinished tasks").isEmpty());
        assertTrue(RetrievalQueryAnalysis.codeHints("怎么保存当前任务？").isEmpty());
    }
    @Test void boundsAndDeduplicatesUntrustedHints() {
        assertEquals(List.of("store.save()"), RetrievalQueryAnalysis.codeHints("store.save() store.save()"));
        assertTrue(RetrievalQueryAnalysis.codeHints("../../outside.java").isEmpty());
        assertTrue(RetrievalQueryAnalysis.codeHints("A".repeat(10000)).isEmpty());
    }
}
