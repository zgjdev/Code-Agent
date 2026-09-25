package com.codeagent.rag.stage;

import com.codeagent.rag.RetrievalCandidate;
import com.codeagent.rag.RetrievalContext;
import com.codeagent.rag.RetrievalSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class GraphRetriever implements CodeRetrieverStage {
    @Override public RetrievalSource source() { return RetrievalSource.GRAPH; }
    @Override public List<RetrievalCandidate> retrieve(RetrievalContext context) throws Exception {
        Set<String> seeds = context.index().searchSymbols(context.request().projectRoot(),
                        context.request().query(), 20).stream()
                .map(RetrievalCandidate::symbolId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return context.index().searchRelations(context.request().projectRoot(), seeds, 20);
    }
}
