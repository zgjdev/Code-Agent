package com.codeagent.runtime.execution;

import com.codeagent.llm.LlmClient;
import com.codeagent.model.recovery.*;
import com.codeagent.prompt.PromptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContinuationIntentRouterTest {
    @TempDir Path temp;

    @Test void largeHistoricalAnswerIsBoundedToTheActualClassifierRequest() throws Exception {
        try (var store = new RuntimeExecutionStore(temp.resolve("runtime.db"))) {
            var request = store.enqueue(temp, "session", "current progress question", null);
            var client = new Client("{\"intent\":\"NEW_TURN\",\"newTurnKind\":\"STATUS_QUESTION\",\"targetExecutionId\":null,\"targetGeneration\":null}");
            var history = List.of(new com.codeagent.history.SessionProjection.ConversationNode(1, null, null, "react",
                    LlmClient.Message.assistant("old history ".repeat(40000) + "LATEST_ROUTING_CONTEXT"),
                    com.codeagent.history.SessionProjection.ConversationKind.ASSISTANT));
            var decision = new ContinuationIntentRouter(client, new PromptRepository(null, null))
                    .route(request, history, snapshot(request), new RecoveryContextBudget(30000, 4096, 2000));
            assertEquals(RequestSemantics.STATUS_QUESTION, decision.semantics());
            assertTrue(com.codeagent.memory.TokenBudget.estimateMessagesTokens(client.messages) <= 30000);
            var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(client.messages.get(1).content());
            assertEquals(request.submittedInput(), data.path("submittedInput").asText());
            assertTrue(data.path("conversationContext").asText().contains("LATEST_ROUTING_CONTEXT"));
            assertTrue(data.path("conversationContext").asText().contains("omitted"));
            assertTrue(data.path("runtimeRecoveryContext").toString().contains("old-1"));
        }
    }

    @Test void insufficientInputBudgetIsNotReportedAsAmbiguousUserIntent() throws Exception {
        try (var store = new RuntimeExecutionStore(temp.resolve("runtime.db"))) {
            var request = store.enqueue(temp, "session", "continue invoice goal", null);
            var client = new Client("{}");
            assertThrows(RecoveryContextBudgetExceededException.class, () -> new ContinuationIntentRouter(client,
                    new PromptRepository(null, null)).route(request, List.of(), snapshot(request), new RecoveryContextBudget(20, 4096, 2000)));
            assertNull(client.messages, "An impossible request must fail before calling the model");
        }
    }

    @Test void exactLocalTargetAndNamedQueuedGoalsReachToolFreeClassifier() throws Exception {
        try (var store = new RuntimeExecutionStore(temp.resolve("runtime.db"))) {
            var request = store.enqueue(temp, "session", "continue invoice goal", null);
            var snapshot = snapshot(request);
            var client = new Client("{\"intent\":\"CONTINUE\",\"newTurnKind\":null,\"targetExecutionId\":\"old-1\",\"targetGeneration\":3}");
            var decision = new ContinuationIntentRouter(client, new PromptRepository(null, null))
                    .route(request, List.of(), snapshot, new RecoveryContextBudget(30000, 4096, 0));
            assertEquals(ContinuationIntent.Kind.CONTINUE, decision.kind());
            assertEquals("old-1", decision.targetExecutionId());
            assertEquals(3, decision.targetGeneration());
            assertNull(client.tools);
            String sent = client.messages.get(1).content();
            assertTrue(sent.contains("invoice goal"));
            assertTrue(sent.contains("search goal"));
            assertEquals(1, sent.split("continue invoice goal", -1).length - 1);
            assertTrue(sent.contains("runtimeRecoveryContext"));
        }
    }

    @Test void statusAndNewRequestRemainDistinctAndInvalidSelectionsClarify() throws Exception {
        try (var store = new RuntimeExecutionStore(temp.resolve("runtime.db"))) {
            var request = store.enqueue(temp, "session", "status?", null);
            var router = new ContinuationIntentRouter(new Client("{\"intent\":\"NEW_TURN\",\"newTurnKind\":\"STATUS_QUESTION\",\"targetExecutionId\":null,\"targetGeneration\":null}"), new PromptRepository(null, null));
            assertEquals(RequestSemantics.STATUS_QUESTION, router.route(request, List.of(), snapshot(request), new RecoveryContextBudget(30000, 4096, 0)).semantics());
            for (String json : List.of(
                    "{\"intent\":\"CONTINUE\",\"newTurnKind\":null,\"targetExecutionId\":\"foreign\",\"targetGeneration\":3}",
                    "{\"intent\":\"CONTINUE\",\"newTurnKind\":null,\"targetExecutionId\":\"old-1\",\"targetGeneration\":2}",
                    "{\"intent\":\"CONTINUE\",\"newTurnKind\":\"NEW_REQUEST\",\"targetExecutionId\":\"old-1\",\"targetGeneration\":3}",
                    "{\"intent\":\"NEW_TURN\",\"newTurnKind\":\"NEW_REQUEST\",\"targetExecutionId\":null,\"targetGeneration\":null,\"extra\":true}",
                    "continue", "{}")) {
                var client = new Client(json);
                assertEquals(ContinuationIntent.Kind.CLARIFY, new ContinuationIntentRouter(client, new PromptRepository(null, null))
                        .route(request, List.of(), snapshot(request), new RecoveryContextBudget(30000, 4096, 0)).kind());
            }
        }
    }
    private RecoveryContextSnapshot snapshot(RuntimeExecution request) {
        return new RecoveryContextSnapshot(request.workspaceKey().value(), request.sessionId(), 1, 1, "a".repeat(64),
                List.of(new RecoveryExecutionState("old-1", "PLAN", "PLAN", "ENQUEUED", "awaiting_input", 3, true, true, "invoice goal"),
                        new RecoveryExecutionState("old-2", "REACT", "REACT", "ENQUEUED", "awaiting_input", 2, false, true, "search goal"),
                        new RecoveryExecutionState(request.id(), null, null, "RUNNING", "ready", 0, false, false, request.submittedInput())),
                List.of(), List.of());
    }
    private static final class Client implements LlmClient {
        final String json; List<Message> messages; List<Tool> tools;
        Client(String json) { this.json = json; }
        public String getProviderName() { return "test"; }
        public String getModelName() { return "test"; }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            this.messages = messages; this.tools = tools;
            return new ChatResponse("assistant", json, null, null, 1, 1, 0);
        }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
    }
}
