package com.codeagent.rag;

import com.codeagent.rag.embedding.*;
import com.codeagent.rag.stage.TermFtsRetriever;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen-corpus retrieval ablation; mixed queries are derived fixtures, not independent user samples. */
@EnabledIfSystemProperty(named="codeagent.search.rag.eval",matches="true")
class RepositorySearchCoordinationEvaluationTest {
    @Test void comparesHintSupplementAndUnifiedSemanticInterleave() throws Exception {
        // Default to validating the delivered policy; the saved ablation predates its implementation.
        boolean finalPolicy=Boolean.parseBoolean(System.getProperty("codeagent.search.rag.final","true"));
        List<String> policies=finalPolicy?List.of("final-production"):List.of("legacy","hints","unified");
        var json=new ObjectMapper();
        Path source=Path.of("target/qwen-migration/production").toAbsolutePath();
        var old=json.readTree(Path.of("target/qwen-recall-optimization/production/results.json").toFile());
        assertEquals("COMPLETE",old.path("metadata").path("status").asText());
        String hash=old.path("metadata").path("corpusSha256").asText(); Path corpus=source.resolve("corpus-"+hash);
        RepositoryEvaluationDatasetTest.validate(corpus);
        assertEquals(old.path("metadata").path("datasetSha256").asText(),Qwen3EvaluationProvider.hash(Files.readAllBytes(Path.of("src/test/resources/rag/repository-evaluation.json"))));
        Path output=Files.createDirectories(Path.of("target/code-search-coordination"));
        Path originalIndex=source.resolve("index-"+hash+".db"); Path wal=Path.of(originalIndex+"-wal");
        assertTrue(!Files.exists(wal)||Files.size(wal)==0,"Checkpoint source WAL before copying frozen index");
        Path database=output.resolve("frozen-index.db"); Files.copy(originalIndex,database,StandardCopyOption.REPLACE_EXISTING);
        List<Map<String,Object>> rows=new ArrayList<>(); Map<String,JsonNode> previous=new HashMap<>();
        Map<String,JsonNode> selectedPolicy=new HashMap<>();
        if(finalPolicy) json.readTree(output.resolve("rag-ablation.json").toFile()).path("rows").forEach(r->{
            if(r.path("policy").asText().equals("unified"))selectedPolicy.put(r.path("id").asText()+":"+r.path("category").asText(),r);
        });
        old.path("rows").forEach(r->{if(r.path("mode").asText().equals("full"))previous.put(r.path("id").asText(),r);});
        try(var provider=new InProcessQwen3EmbeddingProvider(InProcessQwen3EmbeddingProvider.defaultModelDirectory());
            var index=new SqliteRetrievalIndex(database);
            var service=new DefaultCodeRetrievalService(index,new EmbeddingResolution(Optional.of(provider),"local",false))) {
            assertEquals(3226,index.status(corpus).chunkCount());
            for(var item:RepositoryEvaluationDatasetTest.load()) {
                List<String> variants=item.category().equals("identifier")
                        ?List.of(item.query(),"请解释 "+item.query()+" 的实现，说明核心步骤和失败处理。") :List.of(item.query());
                for(int v=0;v<variants.size();v++) {
                    String query=variants.get(v); var request=new RetrievalRequest(corpus,query,10,16000,false,RetrievalIntent.CHUNKS);
                    var vector=provider.embedAll(List.of(new EmbeddingInputPolicy().prepareQuery(query))).get(0);
                    var semantic=index.searchVector(corpus,provider.space().embeddingSpaceId(),vector,100);
                    var lexical=new TermFtsRetriever().retrieve(new RetrievalContext(request,index,Optional.of(provider)));
                    var legacy=legacyLexical(index,corpus,query,40);
                    for(String policy:policies) {
                        Map<RetrievalSource,List<RetrievalCandidate>> ranks=Map.of(RetrievalSource.FTS_TERMS,policy.equals("legacy")?legacy:lexical,RetrievalSource.SEMANTIC_LOCAL,semantic);
                        List<RetrievalHit> hits;
                        if(!policy.equals("unified")) hits=new RetrievalPipeline().apply(ranks,request).hits();
                        else {
                            var primaryRanks=Map.of(RetrievalSource.FTS_TERMS,lexical,RetrievalSource.SEMANTIC_LOCAL,semantic.subList(0,Math.min(30,semantic.size())));
                            // Neutral multiword query disables only the symbol priority and symbol context exclusion.
                            var fused=new RetrievalFusion().fuse(primaryRanks,"semantic evaluation query",30);
                            var primary=new RetrievalBudget().apply(fused,10,16000);
                            hits=new RetrievalContextAssembler().assemble(primary.hits(),ranks,"semantic evaluation query",16000);
                        }
                        assertTrue(hits.size()<=10);assertTrue(hits.stream().mapToInt(h->h.content().length()).sum()<=16000);
                        var metric=RetrievalEvaluationMetrics.measure(item.evidence(),hits,10);
                        if(v==0 && policy.equals("legacy") && !finalPolicy) assertEquals(previous.get(item.id()).path("at10"),json.valueToTree(metric));
                        if(policy.equals("hints") || finalPolicy) {
                            if(finalPolicy) assertEquals(selectedPolicy.get(item.id()+":"+(v==0?item.category():"mixed")).path("at10"),json.valueToTree(metric));
                            var actual=service.search(request);assertEquals(hits,actual.hits());
                            assertTrue(actual.diagnostics().degradedReasonCodes().isEmpty());
                            assertTrue(actual.diagnostics().fileFreshness().values().stream().allMatch("verified"::equals));
                        }
                        rows.add(Map.of("id",item.id(),"category",v==0?item.category():"mixed", "policy",policy,
                                "positive",!item.evidence().isEmpty(),"at10",metric));
                    }
                }
                System.out.println("Coordination ablation completed: "+item.id());
            }
        }
        assertEquals((75+19)*policies.size(),rows.size());
        List<Map<String,Object>> summaries=new ArrayList<>();
        for(String policy:policies) for(String group:List.of("natural","identifier","mixed","main")) {
            var selected=rows.stream().filter(r->r.get("policy").equals(policy)&&Boolean.TRUE.equals(r.get("positive")))
                    .filter(r->group.equals("main")?Set.of("identifier","semantic","paraphrase","cross_module").contains(r.get("category"))
                            :group.equals("natural")?Set.of("semantic","paraphrase","cross_module").contains(r.get("category")):group.equals(r.get("category"))).toList();
            summaries.add(Map.of("policy",policy,"group",group,"count",selected.size(),"recall10",selected.stream()
                    .mapToDouble(r->((RetrievalEvaluationMetrics.Scores)r.get("at10")).recall()).average().orElseThrow()));
        }
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve(finalPolicy?"rag-final.json":"rag-ablation.json").toFile(),Map.of("status","COMPLETE","corpusSha256",hash,"datasetSha256",old.path("metadata").path("datasetSha256").asText(),"summaries",summaries,"rows",rows));
    }
    private static List<RetrievalCandidate> legacyLexical(SqliteRetrievalIndex index,Path root,String query,int limit) throws Exception {
        String normalized=new LexicalTextNormalizer().normalizeQuery(query);
        var strict=index.searchTerms(root,normalized,limit);
        if(strict.size()>=limit||normalized.isBlank()||!normalized.contains(" "))return strict;
        Map<Long,RetrievalCandidate> merged=new LinkedHashMap<>();strict.forEach(c->merged.put(c.chunkId(),c));
        for(var candidate:index.searchAnyTerms(root,normalized,limit)) {merged.putIfAbsent(candidate.chunkId(),candidate);if(merged.size()>=limit)break;}
        return List.copyOf(merged.values());
    }
}
