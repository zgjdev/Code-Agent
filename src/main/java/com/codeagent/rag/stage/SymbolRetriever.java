package com.codeagent.rag.stage;

import com.codeagent.rag.RetrievalCandidate;
import com.codeagent.rag.RetrievalContext;
import com.codeagent.rag.RetrievalSource;

import java.util.List;

public final class SymbolRetriever implements CodeRetrieverStage {
    @Override public RetrievalSource source() { return RetrievalSource.SYMBOL; }
    @Override public List<RetrievalCandidate> retrieve(RetrievalContext context) throws Exception {
        return context.index().searchSymbols(context.request().projectRoot(), context.request().query(), 20);
    }
}
