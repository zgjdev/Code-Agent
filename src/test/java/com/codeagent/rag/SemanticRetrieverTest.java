package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingLocality;
import com.codeagent.rag.embedding.EmbeddingProvider;
import com.codeagent.rag.embedding.EmbeddingSpaceDescriptor;
import com.codeagent.rag.stage.SemanticRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticRetrieverTest {
    @Test
    void reportsRemoteSourceForRemoteProvider(@TempDir Path root) {
        RetrievalContext context = new RetrievalContext(
                new RetrievalRequest(root, "query", 5, 1_000, false, RetrievalIntent.CHUNKS),
                null, Optional.of(remoteProvider()));

        assertEquals(RetrievalSource.SEMANTIC_REMOTE, new SemanticRetriever().source(context));
    }

    private static EmbeddingProvider remoteProvider() {
        EmbeddingSpaceDescriptor space = EmbeddingSpaceDescriptor.create(
                "remote", "model", "endpoint", "1", 2, "mean", true, 1, 1);
        return new EmbeddingProvider() {
            @Override public String id() { return "remote"; }
            @Override public String modelId() { return "model"; }
            @Override public EmbeddingSpaceDescriptor space() { return space; }
            @Override public EmbeddingLocality locality() { return EmbeddingLocality.REMOTE; }
            @Override public List<float[]> embedAll(List<String> inputs) { return List.of(); }
        };
    }
}
