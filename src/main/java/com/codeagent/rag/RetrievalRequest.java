package com.codeagent.rag;

import java.nio.file.Path;

public record RetrievalRequest(Path projectRoot, String query, int topK, int maxChars,
                               boolean includeLiveSearch, RetrievalIntent intent, String lexicalQuery) {
    public static final int DEFAULT_TOP_K = 10;
    public static final int DEFAULT_MAX_CHARS = 16_000;

    public RetrievalRequest(Path projectRoot, String query, int topK, int maxChars,
                            boolean includeLiveSearch, RetrievalIntent intent) {
        this(projectRoot, query, topK, maxChars, includeLiveSearch, intent, null);
    }

    public RetrievalRequest {
        if (topK <= 0) throw new IllegalArgumentException("topK must be positive");
        if (maxChars <= 0) throw new IllegalArgumentException("maxChars must be positive");
        intent = intent == null ? RetrievalIntent.CHUNKS : intent;
        if (lexicalQuery != null && lexicalQuery.length() > 2048)
            throw new IllegalArgumentException("lexical_query exceeds 2048 characters");
    }
}
