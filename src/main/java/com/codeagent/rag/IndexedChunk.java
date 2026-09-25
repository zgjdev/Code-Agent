package com.codeagent.rag;

public record IndexedChunk(
        int startLine,
        int endLine,
        String chunkType,
        String symbol,
        String symbolId,
        String content,
        String searchTerms,
        String contentHash
) {}
