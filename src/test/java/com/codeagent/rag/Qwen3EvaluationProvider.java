package com.codeagent.rag;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import com.codeagent.rag.embedding.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Experiment-only local provider. No default wiring, remote service, or user index changes. */
final class Qwen3EvaluationProvider implements EmbeddingProvider {
    static final String REVISION = "c25a394dd583836952667c12f008335071b3f43d";
    static final String MODEL_SHA = "87cd124e0ef1fd1f223ebc283efccbaeac386d0b08344701c46975d0657b591f";
    static final String TOKENIZER_SHA = "def76fb086971c7867b829c23a26261e38d9d74e02139253b38aeb9df8b4b50a";
    static final String INSTRUCTION = "Given a natural-language software-engineering query, retrieve source-code chunks that implement or explain the described behavior.";
    private static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private static final String DOCUMENT_PREFIX = "代码文档：";
    private static final int FULL_DIMENSION = 1024;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final EmbeddingSpaceDescriptor space;
    private final Path cache;
    private final int dimension;
    private final boolean reference;
    private boolean closed;
    private long inferenceNanos, nativeCalls, documentCacheHits;

    Qwen3EvaluationProvider(Path modelDirectory, int dimension, Path cache) throws Exception {
        this(modelDirectory, dimension, cache, false);
    }

    Qwen3EvaluationProvider(Path modelDirectory, int dimension, Path cache, boolean reference) throws Exception {
        if (dimension != 512 && dimension != 1024) throw new IllegalArgumentException("dimension must be 512 or 1024");
        this.dimension = dimension;
        this.reference = reference;
        this.cache = Files.createDirectories(cache.resolve(REVISION + (reference ? "-fp32" : "") + "-input-v2"));
        String modelFile = reference ? "model.onnx" : "model_quantized.onnx";
        requireHash(modelDirectory.resolve(modelFile), reference
                ? "bf27b2f3f9ef9c32ca337d75b361fa99439deaeaefe82e4701b2dbd8439197cc" : MODEL_SHA);
        if (reference) requireHash(modelDirectory.resolve("model.onnx_data"),
                "f0a61604465929a27e68aa6217c8c89ec6186572f0209fdb7711adda48a9b9a9");
        requireHash(modelDirectory.resolve("tokenizer.json"), TOKENIZER_SHA);
        space = EmbeddingSpaceDescriptor.create(id(), modelId(), "in-process", REVISION + ":" + (reference ? "fp32" : MODEL_SHA),
                dimension, "last-token", true, 2, 1);
        tokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(modelDirectory.resolve("tokenizer.json"))
                .optAddSpecialTokens(true).optPadding(false).optTruncation(false).build();
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(4);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = environment.createSession(modelDirectory.resolve(modelFile).toString(), options);
        } catch (Exception e) { tokenizer.close(); throw e; }
        System.out.println("Qwen ONNX input count=" + session.getInputNames().size()
                + "; hidden output=" + session.getOutputInfo().get("last_hidden_state"));
    }

    static String adapt(String input) {
        Objects.requireNonNull(input);
        if (input.startsWith(QUERY_PREFIX)) return "Instruct: " + INSTRUCTION + "\nQuery: " + input.substring(QUERY_PREFIX.length());
        if (input.startsWith(DOCUMENT_PREFIX)) return input.substring(DOCUMENT_PREFIX.length());
        throw new IllegalArgumentException("Expected explicit BGE query/document boundary");
    }

    @Override public String id() { return "local-qwen3-evaluation"; }
    @Override public String modelId() { return "qwen3-embedding-0.6b-onnx-" + (reference ? "fp32" : "int8"); }
    @Override public EmbeddingSpaceDescriptor space() { return space; }
    @Override public EmbeddingLocality locality() { return EmbeddingLocality.IN_PROCESS; }

    @Override public synchronized List<float[]> embedAll(List<String> inputs) throws EmbeddingException {
        List<float[]> vectors = new ArrayList<>();
        try {
            if (closed) throw new IllegalStateException("Qwen provider is closed");
            for (String input : inputs) {
                String adapted = adapt(input);
                boolean document = input.startsWith(DOCUMENT_PREFIX);
                Path file = cache.resolve(hash(adapted.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".vector");
                float[] full;
                if (document && Files.isRegularFile(file) && Files.size(file) == FULL_DIMENSION * 4L) {
                    ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file));
                    full = new float[FULL_DIMENSION];
                    for (int i = 0; i < full.length; i++) full[i] = buffer.getFloat();
                    documentCacheHits++;
                } else {
                    full = infer(adapted);
                    if (document && nativeCalls % 50 == 0)
                        System.out.println("Qwen native encodings=" + nativeCalls + "; inference ms=" + inferenceNanos / 1_000_000);
                    if (document) {
                        ByteBuffer buffer = ByteBuffer.allocate(FULL_DIMENSION * 4);
                        for (float value : full) buffer.putFloat(value);
                        Path pending = file.resolveSibling(file.getFileName() + ".pending");
                        Files.write(pending, buffer.array());
                        Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                vectors.add(normalizedPrefix(full, dimension));
            }
            return List.copyOf(vectors);
        } catch (Exception e) { throw new EmbeddingException("qwen_evaluation_failed", "Qwen local experiment failed", e); }
    }

    long[] tokenIds(String rawText) { return tokenizer.encode(rawText).getIds(); }

    private float[] infer(String input) throws Exception {
        var encoded = tokenizer.encode(input);
        long[] ids = encoded.getIds(), mask = encoded.getAttentionMask();
        if (ids.length == 0 || ids.length > 8192) throw new IllegalArgumentException("Unexpected Qwen token length: " + ids.length);
        Map<String, OnnxTensor> feeds = new LinkedHashMap<>();
        try {
            for (var entry : session.getInputInfo().entrySet()) {
                String name = entry.getKey();
                if (name.equals("input_ids")) feeds.put(name, OnnxTensor.createTensor(environment, new long[][]{ids}));
                else if (name.equals("attention_mask")) feeds.put(name, OnnxTensor.createTensor(environment, new long[][]{mask}));
                else if (name.equals("position_ids")) {
                    long[] positions = new long[ids.length];
                    for (int i = 0; i < positions.length; i++) positions[i] = i;
                    feeds.put(name, OnnxTensor.createTensor(environment, new long[][]{positions}));
                } else if (name.startsWith("past_key_values.")) {
                    TensorInfo info = (TensorInfo) entry.getValue().getInfo();
                    long[] shape = info.getShape();
                    if (info.type != OnnxJavaType.FLOAT || shape.length != 4 || shape[1] != 8 || shape[3] != 128)
                        throw new IllegalStateException("Unexpected KV contract: " + entry.getValue());
                    feeds.put(name, OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), new long[]{1, 8, 0, 128}));
                } else throw new IllegalStateException("Unsupported ONNX input: " + name);
            }
            long start = System.nanoTime();
            try (var result = session.run(feeds, Set.of("last_hidden_state"))) {
                inferenceNanos += System.nanoTime() - start;
                nativeCalls++;
                var hidden = result.get("last_hidden_state").orElseThrow(
                        () -> new IllegalStateException("Missing last_hidden_state: " + session.getOutputNames()));
                float[][][] values = (float[][][]) hidden.getValue();
                if (values.length != 1 || values[0].length != mask.length)
                    throw new IllegalStateException("Hidden state shape differs from tokens");
                return pool(values[0], mask, FULL_DIMENSION);
            }
        } finally { for (var tensor : feeds.values()) tensor.close(); }
    }

    static float[] pool(float[][] hidden, long[] mask, int dimension) {
        if (hidden.length != mask.length) throw new IllegalArgumentException("mask shape mismatch");
        for (int i = mask.length - 1; i >= 0; i--)
            if (mask[i] == 1) return normalizedPrefix(hidden[i], dimension);
        throw new IllegalArgumentException("no active token");
    }

    private static float[] normalizedPrefix(float[] vector, int dimension) {
        if (dimension <= 0 || dimension > vector.length) throw new IllegalArgumentException("invalid dimension");
        float[] result = Arrays.copyOf(vector, dimension);
        double norm = 0;
        for (float value : result) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("non-finite embedding");
            norm += value * (double) value;
        }
        if (norm == 0) throw new IllegalArgumentException("zero embedding");
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < result.length; i++) result[i] *= scale;
        return result;
    }

    Map<String, Object> statistics() {
        return Map.of("nativeCalls", nativeCalls, "documentCacheHits", documentCacheHits,
                "nativeInferenceMillis", inferenceNanos / 1_000_000, "intraOpThreads", 4,
                "modelRevision", REVISION, "modelSha256", reference
                        ? "bf27b2f3f9ef9c32ca337d75b361fa99439deaeaefe82e4701b2dbd8439197cc" : MODEL_SHA,
                "tokenizerSha256", TOKENIZER_SHA,
                "inputInstruction", INSTRUCTION, "documentPolicy", "remove BGE prefix; retain existing numbered parts");
    }

    static void requireHash(Path file, String expected) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 20];
            for (int n; (n = stream.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) throw new IllegalArgumentException("Artifact checksum mismatch: " + file.getFileName());
    }

    static String hash(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        tokenizer.close();
        try { session.close(); } catch (OrtException e) { throw new IllegalStateException(e); }
    }
}
