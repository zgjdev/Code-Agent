package com.codeagent.rag.embedding;

import java.util.List;

public interface EmbeddingProvider extends AutoCloseable {
    String id();

    String modelId();

    EmbeddingSpaceDescriptor space();

    EmbeddingLocality locality();

    List<float[]> embedAll(List<String> inputs) throws EmbeddingException;

    @Override
    default void close() throws Exception {
    }
}
