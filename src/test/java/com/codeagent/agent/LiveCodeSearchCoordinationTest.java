package com.codeagent.agent;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.llm.*;
import com.codeagent.rag.*;
import com.codeagent.rag.embedding.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** True model tool-selection exercise. Small fixture/answer checks are not a general accuracy benchmark. */
@EnabledIfSystemProperty(named="codeagent.search.live.eval",matches="true")
class LiveCodeSearchCoordinationTest {
    @TempDir Path temp;
    @Test void evaluatesOriginalRequestsThroughRealAgentAndQwen() throws Exception {
        try { evaluate(); }
        catch(Exception | AssertionError failure) {
            Path report=Path.of("target/code-search-coordination/live-agent.json");
            if(Files.exists(report)) {
                var state=(com.fasterxml.jackson.databind.node.ObjectNode)CodeSearchAgentHarness.JSON.readTree(report.toFile());
                state.put("status","FAILED"); state.put("failureType",failure.getClass().getSimpleName());
                CodeSearchAgentHarness.JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),state);
            }
            throw failure;
        }
    }
    private void evaluate() throws Exception {
        Path report=Path.of("target/code-search-coordination/live-agent.json"); Files.createDirectories(report.getParent());
        String runId=UUID.randomUUID().toString();
        CodeSearchAgentHarness.JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),Map.of("status","IN_PROGRESS","runId",runId,"rows",List.of()));
        var config=CodeAgentConfig.load();
        var client=LlmClientFactory.createFromConfig(config);
        assertNotNull(client,"Live evaluation requires a configured tool-capable LLM; never silently replace it with scripted decisions");
        assertTrue(client.supportsTools());
        var fixture=CodeSearchAgentHarness.fixture();
        List<Map<String,Object>> rows=new ArrayList<>();
        String oldMemory=System.getProperty("codeagent.memory.dir");
        String oldLimit=System.getProperty("codeagent.react.hard.max.iterations");
        String oldHome=System.getProperty("user.home");
        Path modelDirectory=InProcessQwen3EmbeddingProvider.defaultModelDirectory();
        Path isolatedHome=Files.createDirectories(temp.resolve("home"));
        System.setProperty("user.home",isolatedHome.toString());
        System.setProperty("codeagent.memory.dir",temp.resolve("memory").toString());
        System.setProperty("codeagent.react.hard.max.iterations","10");
        try {
            for(var item:fixture.path("cases")) {
                Path root=temp.resolve(item.path("id").asText()); CodeSearchAgentHarness.writeFixture(root,fixture);
                var provider=new InProcessQwen3EmbeddingProvider(modelDirectory);
                var registry=new CodeSearchAgentHarness.RecordingRegistry();registry.setProjectPath(root.toString());
                var observed=new CodeSearchAgentHarness.CountingClient(client);
                try(var service=new DefaultCodeRetrievalService(new SqliteRetrievalIndex(root.resolve("fixture.db")),new EmbeddingResolution(Optional.of(provider),"local",false));
                    var renderer=new CodeSearchAgentHarness.SilentRenderer()) {
                    if(!item.path("scenario").asText().equals("empty")) {
                        var refresh=service.refresh(new IndexRefreshRequest(root,false));
                        assertEquals(0,refresh.failedFiles());
                        assertFalse(refresh.reasonCodes().contains("embedding_failed"));
                    }
                    if(item.path("scenario").asText().equals("changed")) Files.writeString(root.resolve("RetryPolicy.java"),fixture.path("files").path("RetryPolicy.java").asText().replace("return 1","return 3"));
                    registry.setCodeRetrievalService(service);
                    var agent=new Agent(observed,registry);agent.setRenderer(renderer);agent.setReturnFinalResponseWhenStreamed(true);
                    long start=System.nanoTime(); String answer;
                    try {answer=agent.run(item.path("query").asText()+"\n请在回答末尾附一个JSON对象，结论字段："+String.join(", ", iterableKeys(item.path("facts")))+"。布尔字段使用true/false，数量字段使用整数；根据源码填写，不猜测。");}
                    finally {agent.getMemoryManager().close();}
                    boolean evidence=CodeSearchAgentHarness.hasEvidence(registry.outputs,item.path("evidence"));
                    boolean answerCheck=CodeSearchAgentHarness.matchesFacts(answer,item.path("facts"));
                    boolean success=registry.outputs.stream().allMatch(r->r.successful());
                    rows.add(Map.of("id",item.path("id").asText(),"evidenceRead",evidence,"answerCheck",answerCheck,
                            "toolSuccess",success,"tools",registry.outputs.stream().map(r->r.name()).toList(),
                            "requests",observed.requests,"elapsedMillis",(System.nanoTime()-start)/1_000_000,
                            "toolOutputChars",registry.outputs.stream().mapToInt(r->r.result().length()).sum(),
                            "reportedInputTokens",observed.inputTokens,"reportedOutputTokens",observed.outputTokens));
                    CodeSearchAgentHarness.JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),Map.of("status","IN_PROGRESS","runId",runId,"provider",client.getProviderName(),"model",client.getModelName(),"rows",rows));
                    System.out.println("Live search case complete: "+item.path("id").asText());
                }
            }
            CodeSearchAgentHarness.JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),Map.of("status","COMPLETE","runId",runId,"provider",client.getProviderName(),"model",client.getModelName(),"rows",rows));
            assertEquals(7,rows.size());
            assertTrue(rows.stream().allMatch(r->Boolean.TRUE.equals(r.get("evidenceRead")) && Boolean.TRUE.equals(r.get("answerCheck")) && Boolean.TRUE.equals(r.get("toolSuccess"))),"See metric-only live report; routing/answer failures must remain visible");
        } finally {
            restore("codeagent.memory.dir",oldMemory); restore("codeagent.react.hard.max.iterations",oldLimit);
            restore("user.home",oldHome);
        }
    }
    private static List<String> iterableKeys(com.fasterxml.jackson.databind.JsonNode facts) {
        List<String> keys=new ArrayList<>(); facts.fieldNames().forEachRemaining(keys::add); return keys;
    }
    private static void restore(String key,String value) {if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
}
