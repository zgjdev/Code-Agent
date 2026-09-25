package com.codeagent.rag;

import java.nio.file.Path;
import java.util.List;

public record FileIndexBatch(
        Path projectRoot,
        Path relativePath,
        String contentHash,
        long sizeBytes,
        long modifiedMillis,
        String language,
        String indexStatus,
        String lastError,
        List<IndexedChunk> chunks,
        List<IndexedSymbol> symbols,
        List<IndexedRelation> relations
) {
    public FileIndexBatch {
        chunks = List.copyOf(chunks);
        symbols = List.copyOf(symbols);
        relations = List.copyOf(relations);
    }
}
