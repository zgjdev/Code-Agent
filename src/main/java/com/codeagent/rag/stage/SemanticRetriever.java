package com.codeagent.rag.stage;

import com.codeagent.rag.RetrievalCandidate;
import com.codeagent.rag.RetrievalContext;
import com.codeagent.rag.RetrievalSource;
import com.codeagent.rag.embedding.EmbeddingInputPolicy;
import com.codeagent.rag.embedding.EmbeddingLocality;
import com.codeagent.rag.embedding.EmbeddingProvider;

import java.util.List;

public final class SemanticRetriever implements CodeRetrieverStage {
    private final EmbeddingInputPolicy inputPolicy = new EmbeddingInputPolicy();

    @Override public RetrievalSource source() { return RetrievalSource.SEMANTIC_LOCAL; }

    public RetrievalSource source(RetrievalContext context) {
        return context.embeddingProvider().map(provider -> provider.locality() == EmbeddingLocality.REMOTE
                ? RetrievalSource.SEMANTIC_REMOTE : RetrievalSource.SEMANTIC_LOCAL)
                .orElse(RetrievalSource.SEMANTIC_LOCAL);
    }

    @Override public List<RetrievalCandidate> retrieve(RetrievalContext context) throws Exception {
        EmbeddingProvider provider = context.embeddingProvider().orElse(null);
        if (provider == null) return List.of();
        float[] query = provider.embedAll(List.of(inputPolicy.prepareQuery(context.request().query()))).get(0);
        return context.index().searchVector(context.request().projectRoot(),
                provider.space().embeddingSpaceId(), query, Math.max(context.request().topK() * 3, 15));
    }
}
