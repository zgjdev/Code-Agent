package com.codeagent.rag;

public record RetrievalIndexStatus(
        boolean initialized,
        boolean legacyDatabasePresent,
        int indexedFileCount,
        int chunkCount
) {}
