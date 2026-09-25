package com.codeagent.rag.stage;

import com.codeagent.rag.RetrievalCandidate;
import com.codeagent.rag.RetrievalContext;
import com.codeagent.rag.RetrievalSource;

import java.util.List;

public interface CodeRetrieverStage {
    RetrievalSource source();
    List<RetrievalCandidate> retrieve(RetrievalContext context) throws Exception;
}
