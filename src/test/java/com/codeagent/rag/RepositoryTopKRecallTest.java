package com.codeagent.rag;

import com.codeagent.rag.embedding.*;
import com.codeagent.rag.stage.TermFtsRetriever;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual request parameter sensitivity, not a cached query latency benchmark. */
@EnabledIfSystemProperty(named="rag.topk.eval",matches="true")
class RepositoryTopKRecallTest {
    @Test void comparesTopKAndCharacterBudgetsOnFrozenCorpus() throws Exception {
        var json=new ObjectMapper(); Path original=Path.of("target/qwen-migration/production").toAbsolutePath();
        var baseline=json.readTree(Path.of("target/qwen-recall-optimization/production/results.json").toFile());
        assertEquals("COMPLETE",baseline.path("metadata").path("status").asText());
        String hash=baseline.path("metadata").path("corpusSha256").asText();
        Path corpus=original.resolve("corpus-"+hash);
        RepositoryEvaluationDatasetTest.validate(corpus);
        String datasetHash=Qwen3EvaluationProvider.hash(Files.readAllBytes(Path.of("src/test/resources/rag/repository-evaluation.json")));
        assertEquals(baseline.path("metadata").path("datasetSha256").asText(),datasetHash);
        Map<String,JsonNode> old=new HashMap<>();baseline.path("rows").forEach(r->old.put(r.path("mode").asText()+":"+r.path("id").asText(),r));
        List<Map<String,Object>> rows=new ArrayList<>();
        try(var provider=new InProcessQwen3EmbeddingProvider(InProcessQwen3EmbeddingProvider.defaultModelDirectory());
            var index=new SqliteRetrievalIndex(original.resolve("index-"+hash+".db"));
            var service=new DefaultCodeRetrievalService(index,new EmbeddingResolution(Optional.of(provider),"local",false))) {
            assertEquals(3226,index.status(corpus).chunkCount());
            for(var item:RepositoryEvaluationDatasetTest.load()) {
                var vector=provider.embedAll(List.of(new EmbeddingInputPolicy().prepareQuery(item.query()))).get(0);
                var semantic=index.searchVector(corpus,provider.space().embeddingSpaceId(),vector,200);
                for(int k:List.of(5,10,15,20)) {
                    var lexical=new TermFtsRetriever().retrieve(new RetrievalContext(new RetrievalRequest(corpus,item.query(),k,16000,false,RetrievalIntent.CHUNKS),index,Optional.of(provider)));
                    var sem=semantic.subList(0,Math.min(semantic.size(),Math.max(k*10,30)));
                    for(int chars: k==5?List.of(24000):List.of(16000,24000)) {
                        var request=new RetrievalRequest(corpus,item.query(),k,chars,false,RetrievalIntent.CHUNKS);
                        for(String mode:List.of("lexical","semantic","full")) {
                            Map<RetrievalSource,List<RetrievalCandidate>> ranks=new EnumMap<>(RetrievalSource.class);
                            if(!mode.equals("semantic"))ranks.put(RetrievalSource.FTS_TERMS,lexical);
                            if(!mode.equals("lexical"))ranks.put(RetrievalSource.SEMANTIC_LOCAL,sem);
                            var result=new RetrievalPipeline().apply(ranks,request);
                            assertTrue(result.hits().size()<=k);
                            int used=result.hits().stream().mapToInt(h->h.content().length()).sum();assertTrue(used<=chars);
                            var atK=RetrievalEvaluationMetrics.measure(item.evidence(),result.hits(),k);
                            var at5=RetrievalEvaluationMetrics.measure(item.evidence(),result.hits(),5);
                            if(k==10 && chars==16000) {
                                var previous=old.get(mode+":"+item.id());assertNotNull(previous);
                                assertEquals(previous.path("at10"),json.valueToTree(atK));
                                assertEquals(previous.path("at5"),json.valueToTree(at5));
                            }
                            if(mode.equals("full") && chars==16000) {
                                var actual=service.search(request);
                                assertEquals(result.hits(),actual.hits());assertTrue(actual.diagnostics().degradedReasonCodes().isEmpty());
                                String tool=SearchResultFormatter.formatForTool(item.query(),actual);
                                for(var hit:actual.hits())assertTrue(tool.contains(hit.content()));
                            }
                            rows.add(Map.of("id",item.id(),"category",item.category(),"query",item.query(),"evidence",item.evidence(),
                                    "mode",mode,"topK",k,"maxChars",chars,"atK",atK,"at5",at5,"returnedChars",used));
                        }
                    }
                }
                System.out.println("TopK completed "+item.id());
            }
        }
        assertEquals(75*7*3,rows.size());
        List<Map<String,Object>> summaries=new ArrayList<>();
        for(int k:List.of(5,10,15,20))for(int chars:k==5?List.of(24000):List.of(16000,24000))for(String mode:List.of("lexical","semantic","full")) {
            var group=rows.stream().filter(r->r.get("topK").equals(k)&&r.get("maxChars").equals(chars)&&r.get("mode").equals(mode)&&!r.get("category").equals("symbol_reference")).toList();
            var main=group.stream().filter(r->!((List<?>)r.get("evidence")).isEmpty()).toList();assertEquals(63,main.size());
            double recall=main.stream().mapToDouble(r->((RetrievalEvaluationMetrics.Scores)r.get("atK")).recall()).average().orElseThrow();
            var ids=main.stream().filter(r->r.get("category").equals("identifier")).toList();
            var summary=Map.<String,Object>of("topK",k,"maxChars",chars,"mode",mode,"positiveCount",63,"recallAtK",recall,
                    "mrrAtK",main.stream().mapToDouble(r->((RetrievalEvaluationMetrics.Scores)r.get("atK")).reciprocalRank()).average().orElseThrow(),
                    "identifierRecall",ids.stream().mapToDouble(r->((RetrievalEvaluationMetrics.Scores)r.get("atK")).recall()).average().orElseThrow(),
                    "absentFalseReturns",group.stream().filter(r->((List<?>)r.get("evidence")).isEmpty()&&((RetrievalEvaluationMetrics.Scores)r.get("atK")).falseReturn()).count());
            summaries.add(summary);System.out.println(summary);
        }
        Path output=Path.of("target/qwen-recall-optimization/topk");Files.createDirectories(output);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(),Map.of("status","COMPLETE","baselineMetadata",baseline.path("metadata"),
                "datasetSha256",datasetHash,"testSourceSha256",Qwen3EvaluationProvider.hash(Files.readAllBytes(Path.of("src/test/java/com/codeagent/rag/RepositoryTopKRecallTest.java"))),
                "note","Same-query vector reused to score request settings, with independent real service verification for all Top10/15/20 at 16000; not a latency benchmark.","rows",rows,"summaries",summaries));
    }
}
