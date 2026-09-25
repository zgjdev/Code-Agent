package com.codeagent.rag.stage;

import com.codeagent.rag.RagQueryTokenizer;
import com.codeagent.rag.RetrievalCandidate;
import com.codeagent.rag.RetrievalContext;
import com.codeagent.rag.RetrievalSource;
import com.codeagent.search.CodeSearchRequest;
import com.codeagent.search.CodeSearchResult;
import com.codeagent.search.ContextLine;
import com.codeagent.search.GrepMatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LiveGrepRetriever implements CodeRetrieverStage {
    private final RagQueryTokenizer tokenizer = new RagQueryTokenizer();
    @Override public RetrievalSource source() { return RetrievalSource.LIVE_GREP; }

    @Override
    public List<RetrievalCandidate> retrieve(RetrievalContext context) {
        if (!context.request().includeLiveSearch() || context.codeSearchService() == null) return List.of();
        Map<String, RetrievalCandidate> results = new LinkedHashMap<>();
        for (String identifier : tokenizer.identifiers(context.request().query())) {
            CodeSearchResult found = context.codeSearchService().search(new CodeSearchRequest(identifier,
                    context.request().projectRoot(), context.request().projectRoot(), null,
                    false, true, 2, Math.max(context.request().topK() * 2, 10), 3));
            for (GrepMatch match : found.matches()) {
                String content = match.context().stream().map(ContextLine::text)
                        .reduce((left, right) -> left + "\n" + right).orElse("");
                int start = match.context().stream().mapToInt(ContextLine::lineNumber).min()
                        .orElse(match.lineNumber());
                int end = match.context().stream().mapToInt(ContextLine::lineNumber).max()
                        .orElse(match.lineNumber());
                RetrievalCandidate candidate = new RetrievalCandidate(0, match.file(), start, end,
                        "live", identifier, null, content, 0, false, null, null);
                results.putIfAbsent(match.file() + ':' + start + ':' + end, candidate);
            }
        }
        return List.copyOf(results.values());
    }
}
