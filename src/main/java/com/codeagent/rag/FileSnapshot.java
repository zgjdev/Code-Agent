package com.codeagent.rag;

public record FileSnapshot(
        String filePath,
        String contentHash,
        long sizeBytes,
        long modifiedMillis,
        String language,
        String indexStatus,
        String lastError
) {}
