package com.codeagent.rag.stage;

import com.codeagent.rag.RetrievalCandidate;
import com.codeagent.rag.RetrievalContext;
import com.codeagent.rag.RetrievalSource;

import java.util.List;

public final class TrigramFtsRetriever implements CodeRetrieverStage {
    @Override public RetrievalSource source() { return RetrievalSource.FTS_TRIGRAM; }
    @Override public List<RetrievalCandidate> retrieve(RetrievalContext context) throws Exception {
        String query = context.request().query().trim();
        if (query.length() < 3) return List.of();
        return context.index().searchTrigram(context.request().projectRoot(), query,
                Math.max(context.request().topK() * 3, 15));
    }
}
