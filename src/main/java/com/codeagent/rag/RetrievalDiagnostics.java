package com.codeagent.rag;

import java.util.List;
import java.util.Map;

public record RetrievalDiagnostics(String embeddingProviderId, Map<RetrievalSource, Long> stageMillis,
                                   Map<RetrievalSource, Integer> stageHits,
                                   List<String> degradedReasonCodes, int schemaVersion,
                                   Map<String, String> fileFreshness, AutoIndexStatus maintenance) {
    public RetrievalDiagnostics(String embeddingProviderId, Map<RetrievalSource, Long> stageMillis,
                                Map<RetrievalSource, Integer> stageHits, List<String> degradedReasonCodes,
                                int schemaVersion, Map<String, String> fileFreshness) {
        this(embeddingProviderId, stageMillis, stageHits, degradedReasonCodes, schemaVersion,
                fileFreshness, AutoIndexStatus.manual());
    }
    public RetrievalDiagnostics(String embeddingProviderId, Map<RetrievalSource, Long> stageMillis,
                                Map<RetrievalSource, Integer> stageHits, List<String> degradedReasonCodes,
                                int schemaVersion) {
        this(embeddingProviderId, stageMillis, stageHits, degradedReasonCodes, schemaVersion, Map.of());
    }
    public RetrievalDiagnostics {
        maintenance = maintenance == null ? AutoIndexStatus.manual() : maintenance;
        fileFreshness = Map.copyOf(fileFreshness);
        stageMillis = Map.copyOf(stageMillis);
        stageHits = Map.copyOf(stageHits);
        degradedReasonCodes = List.copyOf(degradedReasonCodes);
    }
}
