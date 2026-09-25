package com.codeagent.memory;

import com.codeagent.rag.embedding.EmbeddingException;
import com.codeagent.rag.embedding.EmbeddingLocality;
import com.codeagent.rag.embedding.EmbeddingProvider;
import com.codeagent.rag.embedding.EmbeddingSpaceDescriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MemoryTestEmbeddingProvider implements EmbeddingProvider {
    private final Map<String, float[]> vectors = new HashMap<>();
    private final Map<String, Integer> calls = new HashMap<>();
    private final EmbeddingSpaceDescriptor space;
    private boolean fail;

    MemoryTestEmbeddingProvider() {
        this(2);
    }

    MemoryTestEmbeddingProvider(int dimension) {
        this.space = EmbeddingSpaceDescriptor.create(
                "memory-test", "memory-test", "in-process", "test",
                dimension, "test", true, 1, 1);
    }

    MemoryTestEmbeddingProvider vector(String text, float... vector) {
        vectors.put(text, vector.clone());
        return this;
    }

    MemoryTestEmbeddingProvider fail(boolean fail) {
        this.fail = fail;
        return this;
    }

    int calls(String text) {
        return calls.getOrDefault(text, 0);
    }

    @Override public String id() { return "memory-test"; }
    @Override public String modelId() { return "memory-test"; }
    @Override public EmbeddingSpaceDescriptor space() { return space; }
    @Override public EmbeddingLocality locality() { return EmbeddingLocality.IN_PROCESS; }

    @Override
    public List<float[]> embedAll(List<String> inputs) throws EmbeddingException {
        if (fail) {
            throw new EmbeddingException("test_failure", "test embedding failure");
        }
        List<float[]> result = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            calls.merge(input, 1, Integer::sum);
            float[] vector = vectors.get(input);
            if (vector == null) {
                vector = new float[space.dimension()];
                if (vector.length > 0) vector[vector.length - 1] = 1f;
            }
            if (vector.length != space.dimension()) {
                throw new EmbeddingException("test_dimension", "bad test vector");
            }
            result.add(vector.clone());
        }
        return result;
    }
}
