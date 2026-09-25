package com.codeagent.rag;

public record ChunkEmbedding(
        int startLine,
        int endLine,
        String symbolId,
        String sourceContentHash,
        float[] vector
) {
    public ChunkEmbedding {
        vector = vector.clone();
    }

    @Override
    public float[] vector() {
        return vector.clone();
    }
}
