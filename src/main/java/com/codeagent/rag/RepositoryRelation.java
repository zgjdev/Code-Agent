package com.codeagent.rag;

public record RepositoryRelation(String filePath, String fromSymbolId, String toSymbolId,
                                 String targetText, String relationType, int lineNumber) {
    public boolean resolved() { return toSymbolId != null && !toSymbolId.isBlank(); }
}
