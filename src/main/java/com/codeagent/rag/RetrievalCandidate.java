package com.codeagent.rag;

public record RetrievalCandidate(
        long chunkId,
        String filePath,
        int startLine,
        int endLine,
        String chunkType,
        String symbol,
        String symbolId,
        String content,
        double score,
        boolean resolvedRelation,
        String relationType,
        String targetText
) {}
