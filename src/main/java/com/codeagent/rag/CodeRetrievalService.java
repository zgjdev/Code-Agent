package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingResolution;

public interface CodeRetrievalService extends AutoCloseable {
    RetrievalResponse search(RetrievalRequest request);
    IndexRefreshResult refresh(IndexRefreshRequest request);
    RetrievalIndexStatus status();
    void reconfigureEmbedding(EmbeddingResolution resolution);
    @Override void close();
}
