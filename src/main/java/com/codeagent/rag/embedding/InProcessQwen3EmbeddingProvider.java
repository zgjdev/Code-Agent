package com.codeagent.rag.embedding;

import java.nio.file.Path;
import java.util.*;

/** Fixed FP32 model, loaded only when semantic work is requested. No network or query cache. */
public final class InProcessQwen3EmbeddingProvider implements EmbeddingProvider {
    public static final String REVISION = "c25a394dd583836952667c12f008335071b3f43d";
    public static final int DIMENSION = 1024;
    private final EngineFactory factory;
    private final EmbeddingSpaceDescriptor space = EmbeddingSpaceDescriptor.create("local-qwen3",
            "qwen3-embedding-0.6b-onnx-fp32", "in-process", REVISION + ":fp32", DIMENSION,
            "last-token", true, 2, 1);
    private EmbeddingEngine engine;
    private Exception initializationFailure;
    private boolean closed;

    public InProcessQwen3EmbeddingProvider(Path directory) { this(directory, () -> new Qwen3OnnxEngine(directory)); }
    InProcessQwen3EmbeddingProvider(Path directory, EngineFactory factory) {
        Objects.requireNonNull(directory); this.factory = Objects.requireNonNull(factory);
    }
    public static Path defaultModelDirectory() {
        return Path.of(System.getProperty("user.home"), ".codeagent", "models", "qwen3-embedding-0.6b", REVISION);
    }
    @Override public String id() { return space.providerId(); }
    @Override public String modelId() { return space.modelId(); }
    @Override public EmbeddingSpaceDescriptor space() { return space; }
    @Override public EmbeddingLocality locality() { return EmbeddingLocality.IN_PROCESS; }
    @Override public synchronized List<float[]> embedAll(List<String> inputs) throws EmbeddingException {
        Objects.requireNonNull(inputs);
        try {
            if (closed) throw new IllegalStateException("Qwen provider closed");
            if (inputs.isEmpty()) return List.of();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Qwen work interrupted");
            if (initializationFailure != null) throw initializationFailure;
            if (engine == null) {
                try { engine = Objects.requireNonNull(factory.create()); }
                catch (InterruptedException e) { throw e; }
                catch (Exception e) { initializationFailure = e; throw e; }
            }
            List<float[]> vectors = new ArrayList<>(inputs.size());
            for (String input : inputs) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Qwen work interrupted");
                float[] vector = engine.embed(Objects.requireNonNull(input));
                if (vector == null || vector.length != DIMENSION) throw new IllegalArgumentException("Qwen dimension mismatch");
                vectors.add(Qwen3OnnxEngine.normalize(vector));
            }
            return List.copyOf(vectors);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("local_embedding_interrupted", "Qwen embedding interrupted", e);
        } catch (Exception e) {
            throw new EmbeddingException("local_qwen3_embedding_failed", "Qwen unavailable; verify fixed local model installation", e);
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (engine != null) { try { engine.close(); } catch (Exception e) { throw new IllegalStateException("Qwen close failed", e); } }
    }
    @FunctionalInterface interface EngineFactory { EmbeddingEngine create() throws Exception; }
    @FunctionalInterface interface EmbeddingEngine extends AutoCloseable {
        float[] embed(String input) throws Exception;
        @Override default void close() throws Exception {}
    }
}
