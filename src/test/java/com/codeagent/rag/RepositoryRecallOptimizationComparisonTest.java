package com.codeagent.rag;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="rag.recall.compare",matches="true")
class RepositoryRecallOptimizationComparisonTest {
    @Test void preservesEveryPrimaryBodyAndReportsEveryChange() throws Exception {
        var json=new ObjectMapper(); Path root=Path.of("target/qwen-recall-optimization");
        var before=json.readTree(Path.of("target/qwen-migration/production/results.json").toFile());
        var after=json.readTree(root.resolve("production/results.json").toFile());
        assertEquals("COMPLETE",after.path("metadata").path("status").asText());
        assertTrue(after.path("metadata").path("fullModeToolBodiesVerified").asBoolean());
        for(String field:List.of("corpusSha256","datasetSha256","files","topK","maxChars","embeddingSpace"))
            assertEquals(before.path("metadata").path(field),after.path("metadata").path(field),field);
        Map<String,JsonNode> old=new LinkedHashMap<>(); before.path("rows").forEach(r->old.put(key(r),r));
        List<Map<String,Object>> pairs=new ArrayList<>();
        for(var row:after.path("rows")) {
            var baseline=old.get(key(row)); assertNotNull(baseline);
            for(String field:List.of("query","category","evidence")) assertEquals(baseline.path(field),row.path(field));
            var primary=after.path("metadata").path("primaryScores").path(key(row));
            for(String at:List.of("at5","at10")) assertEquals(baseline.path(at),primary.path(at),"unchanged primary metrics "+key(row));
            assertEquals(baseline.path("hits").size(),primary.path("hits").size());
            for(int i=0;i<baseline.path("hits").size();i++)
                for(String field:List.of("path","startLine","endLine","symbol","contentChars","contentSha256","markerOffsets","matchedEvidence"))
                    assertEquals(baseline.path("hits").get(i).path(field),primary.path("hits").get(i).path(field),key(row)+" primary "+field);
            double delta=row.path("at10").path("recall").asDouble()-baseline.path("at10").path("recall").asDouble();
            assertTrue(delta>=0,"context may not destroy previous evidence "+key(row));
            assertTrue(row.path("hits").size()<=10);
            int chars=0;for(var hit:row.path("hits"))chars+=hit.path("contentChars").asInt();assertTrue(chars<=16000);
            pairs.add(Map.of("id",row.path("id").asText(),"mode",row.path("mode").asText(),"category",row.path("category").asText(),
                    "beforeRecall10",baseline.path("at10").path("recall").asDouble(),"afterRecall10",row.path("at10").path("recall").asDouble(),
                    "deltaRecall10",delta,"deltaMRR10",row.path("at10").path("reciprocalRank").asDouble()-baseline.path("at10").path("reciprocalRank").asDouble()));
        }
        assertEquals(225,pairs.size());
        var main=new ArrayList<JsonNode>();after.path("rows").forEach(r->{if(r.path("mode").asText().equals("full")&&!r.path("category").asText().equals("symbol_reference")&&!r.path("evidence").isEmpty())main.add(r);});
        assertEquals(63,main.size());
        assertTrue(main.stream().mapToDouble(r->r.path("at10").path("recall").asDouble()).average().orElseThrow()> .72222223);
        var ids=main.stream().filter(r->r.path("category").asText().equals("identifier")).toList();assertEquals(19,ids.size());
        assertTrue(ids.stream().allMatch(r->r.path("at10").path("complete").asBoolean()));
        json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("paired-comparison.json").toFile(),Map.of("beforeMetadata",before.path("metadata"),"afterMetadata",after.path("metadata"),"pairedRows",pairs));
    }
    private static String key(JsonNode row){return row.path("mode").asText()+":"+row.path("id").asText();}
}
