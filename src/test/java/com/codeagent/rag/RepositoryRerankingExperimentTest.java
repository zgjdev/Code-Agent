package com.codeagent.rag;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in CPU cross-encoder experiment over captured candidates; never sends code to a server. */
@EnabledIfSystemProperty(named="rag.rerank.experiment", matches="true")
class RepositoryRerankingExperimentTest {
    @Test void scoresFrozenCandidates() throws Exception {
        var json = new ObjectMapper();
        Path root = Path.of("target/qwen-recall-optimization");
        Path model = root.resolve("reranker-model");
        assertEquals("3e9a03ed1e966f7c5288dd4230e3d6a9bf5e3a170a06f1f4241c5bca12c6487c", hash(model.resolve("model.onnx")));
        assertEquals("62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626", hash(model.resolve("tokenizer.json")));
        var input = json.readTree(root.resolve("candidate-report.json").toFile());
        var rows = new ArrayList<Map<String,Object>>();
        var env = OrtEnvironment.getEnvironment();
        try (var options = new OrtSession.SessionOptions();
             var tokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(model.resolve("tokenizer.json"))
                     .optAddSpecialTokens(true).optPadding(false).optTruncation(true).optMaxLength(512).build()) {
            options.setIntraOpNumThreads(4); options.setInterOpNumThreads(1);
            try(var session = env.createSession(model.resolve("model.onnx").toString(), options)) {
                System.out.println("Reranker inputs="+session.getInputInfo()+" outputs="+session.getOutputInfo());
                for(var row: input.path("rows")) {
                    String query = row.path("query").asText();
                    var candidates = new LinkedHashMap<Long,RetrievalCandidate>();
                    for(String field:List.of("semanticCandidates","lexicalCandidates"))
                        for(var node:row.path(field)) {
                            var c=json.treeToValue(node,RetrievalCandidate.class); candidates.putIfAbsent(c.chunkId(),c);
                        }
                    var scores = new LinkedHashMap<String,Double>(); long started=System.nanoTime();
                    for(var c:candidates.values()) {
                        var e = tokenizer.encode(query, c.filePath()+"\n"+c.symbol()+"\n"+c.content());
                        assertTrue(e.getIds().length<=512);
                        var feeds = new LinkedHashMap<String,OnnxTensor>();
                        try {
                            for(String name:session.getInputNames()) {
                                long[] data = switch(name) {case "input_ids" -> e.getIds(); case "attention_mask" -> e.getAttentionMask(); case "token_type_ids" -> e.getTypeIds(); default -> throw new IllegalStateException(name);};
                                feeds.put(name,OnnxTensor.createTensor(env,new long[][]{data}));
                            }
                            try(var result=session.run(feeds)) {
                                float[][] logits=(float[][])result.get(0).getValue();
                                assertEquals(1,logits.length); assertEquals(1,logits[0].length); assertTrue(Float.isFinite(logits[0][0]));
                                scores.put(Long.toString(c.chunkId()),(double)logits[0][0]);
                            }
                        } finally {for(var tensor:feeds.values())tensor.close();}
                    }
                    rows.add(Map.of("id",row.path("id").asText(),"query",query,"scores",scores,"elapsedMillis",(System.nanoTime()-started)/1_000_000));
                    json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("reranker-scores.json").toFile(),Map.of("revision","1427fd652930e4ba29e8149678df786c240d8825","maxTokens",512,"rows",rows));
                    System.out.println(row.path("id").asText()+" pairs="+scores.size()+" ms="+rows.get(rows.size()-1).get("elapsedMillis"));
                }
            }
        }
        assertEquals(75,rows.size());
    }
    private static String hash(Path file) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(file)){byte[] buffer=new byte[1<<20];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
