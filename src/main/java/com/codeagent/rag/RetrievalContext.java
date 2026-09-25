package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingProvider;
import com.codeagent.search.CodeSearchService;

import java.util.Optional;

public record RetrievalContext(RetrievalRequest request, SqliteRetrievalIndex index,
                               Optional<EmbeddingProvider> embeddingProvider,
                               CodeSearchService codeSearchService) {
    public RetrievalContext {
        embeddingProvider = embeddingProvider == null ? Optional.empty() : embeddingProvider;
    }
}
