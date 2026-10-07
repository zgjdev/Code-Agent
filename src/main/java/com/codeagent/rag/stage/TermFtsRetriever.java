package com.codeagent.rag.stage;

import com.codeagent.rag.LexicalTextNormalizer;
import com.codeagent.rag.RetrievalCandidate;
import com.codeagent.rag.RetrievalContext;
import com.codeagent.rag.RetrievalSource;
import com.codeagent.rag.RetrievalQueryAnalysis;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TermFtsRetriever implements CodeRetrieverStage {
    private final LexicalTextNormalizer normalizer = new LexicalTextNormalizer();
    @Override public RetrievalSource source() { return RetrievalSource.FTS_TERMS; }
    @Override public List<RetrievalCandidate> retrieve(RetrievalContext context) throws Exception {
        int limit = Math.max(context.request().topK() * 4, 20);
        String query = normalizer.normalizeQuery(context.request().query());
        String explicit = context.request().lexicalQuery();
        String hint = normalizer.normalizeQuery(explicit == null || explicit.isBlank()
                ? String.join(" ", RetrievalQueryAnalysis.codeHints(context.request().query())) : explicit);
        if (hint.isBlank() || hint.equals(query)) return search(context, query, limit);
        Map<Long, RetrievalCandidate> merged = new LinkedHashMap<>();
        // Reserve at least half the lexical pool for the original intent, even for a frequent hint.
        search(context, hint, Math.max(1, limit / 2)).forEach(c -> merged.put(c.chunkId(), c));
        for (var candidate : search(context, query, limit)) {
            merged.putIfAbsent(candidate.chunkId(), candidate);
            if (merged.size() >= limit) break;
        }
        return List.copyOf(merged.values());
    }

    private List<RetrievalCandidate> search(RetrievalContext context, String query, int limit) throws Exception {
        List<RetrievalCandidate> strict = context.index().searchTerms(
                context.request().projectRoot(), query, limit);
        if (strict.size() >= limit || query.isBlank() || !query.contains(" ")) return strict;
        Map<Long, RetrievalCandidate> merged = new LinkedHashMap<>();
        strict.forEach(candidate -> merged.put(candidate.chunkId(), candidate));
        for (RetrievalCandidate candidate : context.index().searchAnyTerms(
                context.request().projectRoot(), query, limit)) {
            merged.putIfAbsent(candidate.chunkId(), candidate);
            if (merged.size() >= limit) break;
        }
        return List.copyOf(merged.values());
    }
}
