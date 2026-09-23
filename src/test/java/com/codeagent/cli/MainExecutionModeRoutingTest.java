package com.codeagent.cli;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.ExecutionModeRouter;
import com.codeagent.agent.RoutingDecision;
import com.codeagent.agent.RoutingSource;
import com.codeagent.history.ConversationLedger;
import com.codeagent.llm.LlmClient;
import com.codeagent.prompt.ModeRouterPromptBuilder;
import com.codeagent.prompt.PromptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MainExecutionModeRoutingTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitOverrideBypassesRouter() {
        StubClient client = new StubClient("{\"mode\":\"plan\"}");

        RoutingDecision decision = Main.selectExecutionMode(
                ExecutionMode.REACT, router(client), "task", List.of());

        assertEquals(ExecutionMode.REACT, decision.mode());
        assertEquals(RoutingSource.EXPLICIT, decision.source());
        assertEquals(0, client.calls);
    }

    @Test
    void ordinaryInputUsesRouter() {
        StubClient client = new StubClient("{\"mode\":\"plan\"}");

        RoutingDecision decision = Main.selectExecutionMode(
                null, router(client), "task", List.of());

        assertEquals(ExecutionMode.PLAN, decision.mode());
        assertEquals(RoutingSource.AUTO_MODEL, decision.source());
        assertEquals(1, client.calls);
    }

    @Test
    void recordsSafeRoutingMetadataWithoutPromptOrUserContent() throws Exception {
        ConversationLedger ledger = ConversationLedger.open(tempDir.resolve("ledger"), "routing");
        StubClient client = new StubClient("{\"mode\":\"plan\"}");
        RoutingDecision decision = Main.selectExecutionMode(
                null, router(client), "secret task", List.of());

        Main.recordExecutionModeSelection(ledger, decision, client);

        ConversationLedger.Entry entry = ledger.readAll().get(0);
        assertEquals("execution_mode_selected", entry.event());
        assertEquals("router", entry.mode());
        assertEquals("mode-router", entry.actor());
        assertEquals("auto_model", entry.source());
        assertEquals("plan", entry.metadata().get("selectedMode"));
        assertEquals("test-provider", entry.metadata().get("provider"));
        assertEquals("test-model", entry.metadata().get("model"));
        assertEquals(11, entry.metadata().get("inputTokens"));
        assertFalse(entry.metadata().toString().contains("secret task"));
        assertFalse(entry.metadata().containsKey("prompt"));
    }

    private ExecutionModeRouter router(StubClient client) {
        return new ExecutionModeRouter(client, new ModeRouterPromptBuilder(new PromptRepository(
                tempDir.resolve("user-prompts"), tempDir.resolve("project-prompts"))));
    }

    private static final class StubClient implements LlmClient {
        private final String content;
        private int calls;

        private StubClient(String content) {
            this.content = content;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            calls++;
            return new ChatResponse("assistant", content, null, null, 11, 2, 3);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test-provider";
        }
    }
}
