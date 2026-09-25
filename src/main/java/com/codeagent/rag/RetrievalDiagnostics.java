package com.codeagent.rag;

import java.util.List;
import java.util.Map;

public record RetrievalDiagnostics(String embeddingProviderId, Map<RetrievalSource, Long> stageMillis,
                                   Map<RetrievalSource, Integer> stageHits,
                                   List<String> degradedReasonCodes, int schemaVersion) {
    public RetrievalDiagnostics {
        stageMillis = Map.copyOf(stageMillis);
        stageHits = Map.copyOf(stageHits);
        degradedReasonCodes = List.copyOf(degradedReasonCodes);
    }
}
