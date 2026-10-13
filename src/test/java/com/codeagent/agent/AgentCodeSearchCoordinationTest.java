package com.codeagent.agent;

import com.codeagent.llm.LlmClient;
import com.codeagent.rag.*;
import com.codeagent.rag.embedding.EmbeddingResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentCodeSearchCoordinationTest {
    @TempDir Path temp;
    @Test void realToolsCarryMixedUserIntentThroughGrepAndCurrentRead() throws Exception {
        run("看一下 store.save() 这个方法，解释保存前有哪些检查。",false,List.of(
                tool("grep_code","{\"pattern\":\"save\",\"glob\":\"**/*.java\"}"),
                tool("read_file","{\"path\":\"Store.java\",\"offset\":1,\"limit\":30}")),"transaction required");
    }
    @Test void emptyRagIndexCanBeFollowedByLiveSearchWithoutHiddenRefresh() throws Exception {
        run("程序中断后如何恢复未完成任务？",true,List.of(
                tool("search_code","{\"query\":\"从磁盘恢复未完成任务\"}"),
                tool("grep_code","{\"pattern\":\"checkpoint\",\"case_sensitive\":false}"),
                tool("read_file","{\"path\":\"CheckpointRecovery.java\",\"offset\":1,\"limit\":30}")),"Files.readAllLines(checkpoint)");
    }
    private void run(String input,boolean empty,List<LlmClient.ChatResponse> responses,String expectedBody) throws Exception {
        Path root=temp.resolve("project"); CodeSearchAgentHarness.writeFixture(root,CodeSearchAgentHarness.fixture());
        String old=System.getProperty("codeagent.memory.dir"); System.setProperty("codeagent.memory.dir",temp.resolve("memory").toString());
        var script=new ScriptClient(responses,expectedBody);
        var registry=new CodeSearchAgentHarness.RecordingRegistry(); registry.setProjectPath(root.toString());
        try(var service=new DefaultCodeRetrievalService(new SqliteRetrievalIndex(temp.resolve("index.db")),new EmbeddingResolution(Optional.empty(),"off",false));
            var renderer=new CodeSearchAgentHarness.SilentRenderer()) {
            if(!empty) service.refresh(new IndexRefreshRequest(root,false)); registry.setCodeRetrievalService(service);
            var agent=new Agent(script,registry); agent.setRenderer(renderer);
            try {assertEquals("已核对当前源码",agent.run(input));} finally {agent.getMemoryManager().close();}
            assertEquals(input,script.originalInput);
            assertTrue(registry.outputs.stream().allMatch(r->r.successful()));
            assertFalse(registry.readFiles().isEmpty());
            if(empty) {
                assertTrue(registry.outputs.get(0).result().contains("index_empty"));
                assertEquals(0,service.status().chunkCount());
            }
        } finally {if(old==null)System.clearProperty("codeagent.memory.dir");else System.setProperty("codeagent.memory.dir",old);}
    }
    private static LlmClient.ChatResponse tool(String name,String args) {
        return new LlmClient.ChatResponse("assistant","",List.of(new LlmClient.ToolCall(UUID.randomUUID().toString(),new LlmClient.ToolCall.Function(name,args))),10,2);
    }
    private static final class ScriptClient implements LlmClient {
        final Queue<ChatResponse> responses; final String expectedBody; String originalInput;
        ScriptClient(List<ChatResponse> responses,String expectedBody) {this.responses=new ArrayDeque<>(responses);this.expectedBody=expectedBody;}
        public ChatResponse chat(List<Message> messages,List<Tool> tools) throws IOException {return chat(messages,tools,StreamListener.NO_OP);}
        public ChatResponse chat(List<Message> messages,List<Tool> tools,StreamListener listener) {
            if(originalInput==null) originalInput=messages.stream().filter(m->m.role().equals("user")).findFirst().orElseThrow().content();
            if(!responses.isEmpty())return responses.remove();
            assertTrue(messages.stream().filter(m->m.role().equals("tool")).anyMatch(m->m.content().contains(expectedBody)));
            return new ChatResponse("assistant","已核对当前源码",null,10,2);
        }
        public String getModelName(){return "scripted-search-flow";} public String getProviderName(){return "test";}
    }
}
