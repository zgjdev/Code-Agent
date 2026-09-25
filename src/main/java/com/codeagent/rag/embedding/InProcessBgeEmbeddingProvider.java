package com.codeagent.rag.embedding;

import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Bundled, lazy, in-process BGE-small-zh-v1.5 quantized embedding provider. */
public final class InProcessBgeEmbeddingProvider implements EmbeddingProvider {
    public static final String PROVIDER_ID = "local-bge";
    public static final String MODEL_ID = "bge-small-zh-v1.5-q";
    public static final int DIMENSION = 512;
    public static final String ARTIFACT_REVISION = "1.18.0-beta28";

    private final Supplier<EmbeddingEngine> engineFactory;
    private final EmbeddingSpaceDescriptor space;
    private final ExecutorService executor;
    private volatile EmbeddingEngine engine;

    public InProcessBgeEmbeddingProvider() {
        this(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "codeagent-local-embedding");
            thread.setDaemon(true);
            return thread;
        }));
    }

    private InProcessBgeEmbeddingProvider(ExecutorService executor) {
        this(() -> {
            BgeSmallZhV15QuantizedEmbeddingModel model =
                    new BgeSmallZhV15QuantizedEmbeddingModel(executor);
            return text -> model.embed(text).content().vector();
        }, DIMENSION, ARTIFACT_REVISION, executor);
    }

    InProcessBgeEmbeddingProvider(
            Supplier<EmbeddingEngine> engineFactory, int dimension, String artifactRevision) {
        this(engineFactory, dimension, artifactRevision, null);
    }

    private InProcessBgeEmbeddingProvider(Supplier<EmbeddingEngine> engineFactory,
            int dimension, String artifactRevision, ExecutorService executor) {
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        this.executor = executor;
        this.space = EmbeddingSpaceDescriptor.create(PROVIDER_ID, MODEL_ID, "in-process",
                Objects.requireNonNull(artifactRevision, "artifactRevision"), dimension,
                "model-defined", true, 1, 1);
    }

    @Override public String id() { return PROVIDER_ID; }
    @Override public String modelId() { return MODEL_ID; }
    @Override public EmbeddingSpaceDescriptor space() { return space; }
    @Override public EmbeddingLocality locality() { return EmbeddingLocality.IN_PROCESS; }

    @Override
    public List<float[]> embedAll(List<String> inputs) throws EmbeddingException {
        Objects.requireNonNull(inputs, "inputs");
        List<float[]> vectors = new ArrayList<>(inputs.size());
        try {
            EmbeddingEngine activeEngine = engine();
            for (String input : inputs) {
                float[] vector = activeEngine.embed(Objects.requireNonNull(input, "input"));
                if (vector == null || vector.length != space.dimension()) {
                    throw new EmbeddingException("local_embedding_dimension_mismatch",
                            "Local embedding returned an unexpected vector dimension");
                }
                vectors.add(vector.clone());
            }
            return List.copyOf(vectors);
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("local_embedding_failed", "Local embedding failed", e);
        }
    }

    private EmbeddingEngine engine() {
        EmbeddingEngine value = engine;
        if (value != null) return value;
        synchronized (this) {
            if (engine == null) engine = Objects.requireNonNull(engineFactory.get(), "embedding engine");
            return engine;
        }
    }

    @Override
    public void close() {
        if (executor != null) executor.shutdownNow();
    }

    @FunctionalInterface
    interface EmbeddingEngine {
        float[] embed(String text) throws Exception;
    }
}
