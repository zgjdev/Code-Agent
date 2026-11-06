package com.codeagent.agent;

import com.codeagent.config.ContextManagementConfig;
import com.codeagent.history.SessionStore;
import com.codeagent.history.SessionEvent;
import com.codeagent.llm.LlmClient;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentLayeredContextTest {
    @TempDir Path temporary;

    @Test void uniqueReadsAreReducedInRequestsAndRawEvidenceSurvivesRestart() throws Exception {
        String prior = System.getProperty(ContextManagementConfig.PROPERTY_KEY);
        System.setProperty(ContextManagementConfig.PROPERTY_KEY, "true");
        try {
            Path workspace = Files.createDirectory(temporary.resolve("workspace"));
            for (int i = 0; i < 4; i++) Files.writeString(workspace.resolve("file" + i + ".txt"),
                    "source" + i + " " + "unique-observation ".repeat(2500));
            var client = new ReadClient();
            String sessionId;
            try (var store = SessionStore.open(temporary.resolve("history"));
                 var session = store.create(new SessionStore.SessionCreateRequest(workspace, "fixture", "fixture", null, "react", "agent"))) {
                sessionId = session.sessionId();
                ToolRegistry registry = new ToolRegistry() {
                    @Override public List<LlmClient.Tool> getToolDefinitions() {
                        return super.getToolDefinitions().stream().filter(tool -> tool.name().equals("read_file")).toList();
                    }
                };
                registry.setProjectPath(workspace.toString());
                Agent agent = new Agent(client, registry);
                agent.attachSession(session);
                assertEquals("done", agent.run("Read file0.txt through file3.txt and report."));
                assertTrue(client.requests.stream().flatMap(List::stream).anyMatch(message ->
                        message.content() != null && message.content().contains("历史工具正文已缩减")));
                assertEquals(4, session.readAll().stream().filter(event -> event.type().equals(SessionEvent.Types.TOOL_RESULT))
                        .filter(event -> event.payload().path("message").path("content").asText().length() > 40_000).count());
                assertTrue(session.projection().contextScopes().get(sessionId).activeEdits().size() > 0);
                long sequence = session.projection().lastAppliedSequence();
                int calls = client.requests.size();
                agent.getContextStatus();
                agent.currentContextPrediction();
                assertEquals(sequence, session.projection().lastAppliedSequence());
                assertEquals(calls, client.requests.size());
            }
            try (var store = SessionStore.open(temporary.resolve("history"));
                 var session = store.resumeWritable(sessionId, workspace)) {
                assertTrue(session.projection().contextScopes().get(sessionId).activeEdits().size() > 0);
            }
        } finally {
            if (prior == null) System.clearProperty(ContextManagementConfig.PROPERTY_KEY);
            else System.setProperty(ContextManagementConfig.PROPERTY_KEY, prior);
        }
    }

    private static final class ReadClient implements LlmClient {
        private final List<List<Message>> requests = new ArrayList<>();
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            int index = requests.size();
            requests.add(List.copyOf(messages));
            if (index >= 4) return new ChatResponse("assistant", "done", null, null, 0, 0, 0);
            return new ChatResponse("assistant", null, null, List.of(new ToolCall("call" + index,
                    new ToolCall.Function("read_file", "{\"path\":\"file" + index + ".txt\"}"))), 0, 0, 0);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }
        @Override public String getModelName() { return "fixture"; }
        @Override public String getProviderName() { return "fixture"; }
        @Override public int maxContextWindow() { return 64_000; }
    }
}
