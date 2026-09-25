package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingSpaceDescriptor;

import java.nio.file.Path;
import java.util.List;

public record FileEmbeddingBatch(
        Path projectRoot,
        Path relativePath,
        EmbeddingSpaceDescriptor space,
        List<ChunkEmbedding> embeddings
) {
    public FileEmbeddingBatch {
        embeddings = List.copyOf(embeddings);
    }
}
