package com.codeagent.rag;

import java.util.Set;

public record RetrievalHit(String filePath, int startLine, int endLine, String chunkType,
                           String symbol, String content, double score,
                           Set<RetrievalSource> sources) {
    public RetrievalHit { sources = Set.copyOf(sources); }
}
