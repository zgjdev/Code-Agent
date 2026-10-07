package com.codeagent.rag;

import com.codeagent.rag.embedding.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalContextAssemblyTest {
    @Test void preservesAllPrimaryTextAndOrderAndOnlyUsesWholeCompanions() {
        var first=hit("A.java",10,"mainA"); var second=hit("B.java",20,"mainB");
        var extra=candidate("A.java",40,"extraBody");
        var original=List.of(first,second);
        var hits=new RetrievalContextAssembler().assemble(original,Map.of(RetrievalSource.SEMANTIC_LOCAL,List.of(extra)),"如何恢复任务",200);
        assertEquals(2,hits.size()); assertTrue(hits.get(0).content().startsWith(first.content()));
        assertTrue(hits.get(0).content().contains(extra.content())); assertEquals(second,hits.get(1));
        assertEquals(10,hits.get(0).startLine()); assertEquals(42,hits.get(0).endLine());
        assertEquals("context",hits.get(0).chunkType()); assertEquals(first.symbol(),hits.get(0).symbol());
        assertEquals(Set.of(RetrievalSource.FTS_TERMS,RetrievalSource.SEMANTIC_LOCAL),hits.get(0).sources());
        assertEquals(original,new RetrievalContextAssembler().assemble(original,Map.of(RetrievalSource.SEMANTIC_LOCAL,List.of(extra)),"如何恢复任务",10));
    }
    @Test void skipsOversizedAndUnselectedFilesAndDuplicatesAndLimitsEachFileToTwo() {
        var first=hit("A.java",10,"mainA");
        var extras=List.of(candidate("Other.java",1,"wrongFile"),candidate("A.java",1,"x".repeat(1000)),
                candidate("A.java",10,"mainA"),candidate("A.java",20,"second"),candidate("A.java",30,"third"),candidate("A.java",40,"fourth"));
        var hits=new RetrievalContextAssembler().assemble(List.of(first),Map.of(RetrievalSource.SEMANTIC_REMOTE,extras),"如何恢复任务",300);
        assertTrue(hits.get(0).content().contains("second")); assertTrue(hits.get(0).content().contains("third"));
        assertFalse(hits.get(0).content().contains("fourth")); assertFalse(hits.get(0).content().contains("wrongFile"));
        assertEquals(1,hits.get(0).content().split("mainA",-1).length-1);
        assertTrue(hits.get(0).sources().contains(RetrievalSource.SEMANTIC_REMOTE));
    }
    @Test void identifierAndLexicalOnlyReturnOriginalResults() {
        var primary=List.of(hit("A.java",10,"primary"));
        var ranks=Map.of(RetrievalSource.SEMANTIC_LOCAL,List.of(candidate("A.java",20,"extra")));
        for(String query:List.of("Worker","Worker actionOne","resumePendingTasks()"))
            assertEquals(primary,new RetrievalContextAssembler().assemble(primary,ranks,query,1000));
        assertEquals(primary,new RetrievalContextAssembler().assemble(primary,Map.of(),"恢复任务",1000));
    }
    @Test void overlappingClassRangeCannotRemoveMethodBody() {
        var header=new RetrievalHit("A.java",1,100,"class","A","class A {",1,Set.of(RetrievalSource.SEMANTIC_LOCAL));
        var hits=new RetrievalContextAssembler().assemble(List.of(header),Map.of(RetrievalSource.SEMANTIC_LOCAL,
                List.of(candidate("A.java",50,"void run() { recover(); }"))),"如何恢复任务",1000);
        assertTrue(hits.get(0).content().contains("recover();"));
    }
    private static RetrievalHit hit(String file,int line,String body) {
        return new RetrievalHit(file,line,line+2,"method","m"+line,body,1,Set.of(RetrievalSource.FTS_TERMS));
    }
    private static RetrievalCandidate candidate(String file,int line,String body) {
        return new RetrievalCandidate(line,file,line,line+2,"method","m"+line,"id"+line,body,1,false,null,null);
    }
    @Test void serviceAddsMissingSiblingBodyWithinSpareBudget(@TempDir Path temp) throws Exception {
        Path root=Files.createDirectories(temp.resolve("project"));
        StringBuilder source=new StringBuilder("class Worker {\n");
        for(int i=0;i<6;i++) source.append("// ").append("x".repeat(100)).append("\n");
        for(int i=1;i<=8;i++) source.append("void action"+i+"() {\n System.out.println(\"body"+i+"\");\n}\n");
        source.append("}\n");
        Files.writeString(root.resolve("Worker.java"),source);
        try(var service=new DefaultCodeRetrievalService(new SqliteRetrievalIndex(temp.resolve("index.db")),
                new EmbeddingResolution(Optional.of(provider()),"test",false))) {
            assertEquals(0,service.refresh(new IndexRefreshRequest(root,false)).failedFiles());
            var result=service.search(new RetrievalRequest(root,"如何处理延迟任务",10,16000,false,RetrievalIntent.CHUNKS));
            assertTrue(result.hits().stream().anyMatch(h->h.content().contains("body4")),"same-file fourth method must be recovered as complete context");
            assertTrue(result.hits().stream().anyMatch(h->h.chunkType().equals("context")));
            assertTrue(result.hits().stream().mapToInt(h->h.content().length()).sum()<=16000);
            var registry=new com.codeagent.tool.ToolRegistry();
            registry.setProjectPath(root.toString()); registry.setCodeRetrievalService(service);
            var tool=registry.executeTools(List.of(new com.codeagent.tool.ToolRegistry.ToolInvocation(
                    "context", "search_code", "{\"query\":\"如何处理延迟任务\",\"top_k\":10}"))).get(0);
            assertTrue(tool.successful());
            assertTrue(tool.result().contains("body4"),"assembled sibling evidence must reach actual tool output");
        }
    }
    private static EmbeddingProvider provider() {
        var space=EmbeddingSpaceDescriptor.create("toy","toy","in-process","1",2,"test",true,1,1);
        return new EmbeddingProvider() {
            public String id(){return "toy";} public String modelId(){return "toy";}
            public EmbeddingSpaceDescriptor space(){return space;}
            public EmbeddingLocality locality(){return EmbeddingLocality.IN_PROCESS;}
            public List<float[]> embedAll(List<String> inputs){return inputs.stream().map(s->new float[]{1,0}).toList();}
        };
    }
}
