package com.codeagent.rag.embedding;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Pinned ONNX/tokenizer contract; owns tokenizer/session, never the process-global environment. */
final class Qwen3OnnxEngine implements InProcessQwen3EmbeddingProvider.EmbeddingEngine {
    static final String INSTRUCTION = "Given a natural-language software-engineering query, retrieve source-code chunks that implement or explain the described behavior.";
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final HuggingFaceTokenizer tokenizer;
    private final OrtSession session;
    Qwen3OnnxEngine(Path directory) throws Exception {
        requireHash(directory.resolve("model.onnx"), "bf27b2f3f9ef9c32ca337d75b361fa99439deaeaefe82e4701b2dbd8439197cc");
        requireHash(directory.resolve("model.onnx_data"), "f0a61604465929a27e68aa6217c8c89ec6186572f0209fdb7711adda48a9b9a9");
        requireHash(directory.resolve("tokenizer.json"), "def76fb086971c7867b829c23a26261e38d9d74e02139253b38aeb9df8b4b50a");
        tokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(directory.resolve("tokenizer.json"))
                .optAddSpecialTokens(true).optPadding(false).optTruncation(false).build();
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(4); options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = environment.createSession(directory.resolve("model.onnx").toString(), options);
        } catch (Exception e) { tokenizer.close(); throw e; }
    }
    static String adapt(String input) {
        String query = "为这个句子生成表示以用于检索相关文章：", document = "代码文档：";
        if (input.startsWith(query)) return "Instruct: " + INSTRUCTION + "\nQuery: " + input.substring(query.length());
        if (input.startsWith(document)) return input.substring(document.length());
        throw new IllegalArgumentException("Expected explicit query/document input boundary");
    }
    @Override public float[] embed(String input) throws Exception {
        var encoding = tokenizer.encode(adapt(input));
        long[] ids = encoding.getIds(), mask = encoding.getAttentionMask();
        if (ids.length == 0 || ids.length > 8192 || mask.length != ids.length)
            throw new IllegalArgumentException("Qwen token budget/shape invalid");
        Map<String, OnnxTensor> feeds = new LinkedHashMap<>();
        try {
            for (var entry : session.getInputInfo().entrySet()) {
                String name = entry.getKey();
                if (name.equals("input_ids")) feeds.put(name, OnnxTensor.createTensor(environment, new long[][]{ids}));
                else if (name.equals("attention_mask")) feeds.put(name, OnnxTensor.createTensor(environment, new long[][]{mask}));
                else if (name.equals("position_ids")) {
                    long[] positions = new long[ids.length]; for (int i = 0; i < ids.length; i++) positions[i] = i;
                    feeds.put(name, OnnxTensor.createTensor(environment, new long[][]{positions}));
                } else if (name.startsWith("past_key_values.")) {
                    TensorInfo info = (TensorInfo) entry.getValue().getInfo(); long[] shape = info.getShape();
                    if (info.type != OnnxJavaType.FLOAT || shape.length != 4 || shape[1] != 8 || shape[3] != 128)
                        throw new IllegalStateException("Qwen KV shape invalid");
                    feeds.put(name, OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), new long[]{1,8,0,128}));
                } else throw new IllegalStateException("Unsupported Qwen ONNX input");
            }
            try (var result = session.run(feeds, Set.of("last_hidden_state"))) {
                float[][][] hidden = (float[][][]) result.get("last_hidden_state").orElseThrow().getValue();
                if (hidden.length != 1 || hidden[0].length != mask.length)
                    throw new IllegalStateException("Qwen hidden shape invalid");
                float[] vector = pool(hidden[0], mask);
                if (vector.length != InProcessQwen3EmbeddingProvider.DIMENSION) throw new IllegalStateException("Qwen dimension invalid");
                return vector;
            }
        } finally { for (var tensor : feeds.values()) tensor.close(); }
    }
    static float[] pool(float[][] hidden, long[] mask) {
        if (hidden.length != mask.length) throw new IllegalArgumentException("Qwen mask shape invalid");
        for (int i = mask.length - 1; i >= 0; i--) if (mask[i] == 1) return normalize(hidden[i]);
        throw new IllegalArgumentException("Qwen no active token");
    }
    static float[] normalize(float[] input) {
        float[] vector = input.clone(); double norm = 0;
        for (float v : vector) { if (!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite Qwen vector"); norm += v * (double) v; }
        if (norm == 0) throw new IllegalArgumentException("Zero Qwen vector");
        float scale = (float) (1 / Math.sqrt(norm)); for (int i = 0; i < vector.length; i++) vector[i] *= scale;
        return vector;
    }
    static void requireHash(Path file, String expected) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 20]; for (int n; (n = stream.read(buffer)) != -1;) digest.update(buffer,0,n);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) throw new IllegalArgumentException("Qwen artifact checksum mismatch: " + file.getFileName());
    }
    @Override public void close() throws Exception { try { session.close(); } finally { tokenizer.close(); } }
}
