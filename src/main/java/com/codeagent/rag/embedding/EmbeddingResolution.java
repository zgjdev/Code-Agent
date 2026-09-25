package com.codeagent.rag.embedding;

import java.util.Objects;
import java.util.Optional;

public record EmbeddingResolution(
        Optional<EmbeddingProvider> provider,
        String reason,
        boolean consentRequired
) {
    public EmbeddingResolution {
        provider = provider == null ? Optional.empty() : provider;
        reason = Objects.requireNonNullElse(reason, "unspecified");
    }
}
