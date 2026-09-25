package com.codeagent.rag;

import java.util.List;
import java.util.Optional;

public record RetrievalResponse(List<RetrievalHit> hits, Optional<RepositoryMap> repositoryMap,
                                RetrievalDiagnostics diagnostics, boolean partial) {
    public RetrievalResponse {
        hits = List.copyOf(hits);
        repositoryMap = repositoryMap == null ? Optional.empty() : repositoryMap;
    }
}
