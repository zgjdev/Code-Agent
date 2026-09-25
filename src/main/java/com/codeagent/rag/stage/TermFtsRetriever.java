package com.codeagent.rag.stage;

import com.codeagent.rag.LexicalTextNormalizer;
import com.codeagent.rag.RetrievalCandidate;
import com.codeagent.rag.RetrievalContext;
import com.codeagent.rag.RetrievalSource;

import java.util.List;

public final class TermFtsRetriever implements CodeRetrieverStage {
    private final LexicalTextNormalizer normalizer = new LexicalTextNormalizer();
    @Override public RetrievalSource source() { return RetrievalSource.FTS_TERMS; }
    @Override public List<RetrievalCandidate> retrieve(RetrievalContext context) throws Exception {
        int limit = Math.max(context.request().topK() * 4, 20);
        return context.index().searchTerms(context.request().projectRoot(),
                normalizer.normalize(context.request().query()), limit);
    }
}
