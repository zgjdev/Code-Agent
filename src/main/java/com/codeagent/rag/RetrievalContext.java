package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingProvider;
import com.codeagent.search.CodeSearchService;

import java.util.Optional;

public record RetrievalContext(RetrievalRequest request, SqliteRetrievalIndex index,
                               Optional<EmbeddingProvider> embeddingProvider) {
    public RetrievalContext {
        embeddingProvider = embeddingProvider == null ? Optional.empty() : embeddingProvider;
    }

    /**
     * @deprecated live code search is no longer available to retrieval stages. The final argument
     * is ignored and retained only for callers compiled against the previous record constructor.
     */
    @Deprecated(forRemoval = false)
    public RetrievalContext(RetrievalRequest request, SqliteRetrievalIndex index,
            Optional<EmbeddingProvider> embeddingProvider, CodeSearchService ignoredCodeSearchService) {
        this(request, index, embeddingProvider);
    }
}
