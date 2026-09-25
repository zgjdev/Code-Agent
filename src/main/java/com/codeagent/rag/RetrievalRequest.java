package com.codeagent.rag;

import java.nio.file.Path;

public record RetrievalRequest(Path projectRoot, String query, int topK, int maxChars,
                               boolean includeLiveSearch, RetrievalIntent intent) {
    public RetrievalRequest {
        if (topK <= 0) throw new IllegalArgumentException("topK must be positive");
        if (maxChars <= 0) throw new IllegalArgumentException("maxChars must be positive");
        intent = intent == null ? RetrievalIntent.CHUNKS : intent;
    }
}
