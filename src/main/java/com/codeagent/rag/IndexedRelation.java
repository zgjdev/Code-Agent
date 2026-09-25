package com.codeagent.rag;

public record IndexedRelation(
        String fromSymbolId,
        String toSymbolId,
        String targetText,
        String relationType,
        int lineNumber
) {}
