package com.codeagent.runtime.execution;

import com.codeagent.history.SessionProjection;
import com.codeagent.llm.LlmClient;
import com.codeagent.model.recovery.*;
import com.codeagent.prompt.PromptRepository;
import com.codeagent.prompt.ModeRouterPromptBuilder;
import com.codeagent.prompt.RecoveryContextFormatter;
import com.codeagent.llm.StructuredJsonExecutor;
import com.codeagent.llm.StructuredOutputSpec;
import com.codeagent.runtime.CancellationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Read-only intent classifier. Only the admission service can release a task. */
public final class ContinuationIntentRouter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LlmClient client;
    private final PromptRepository prompts;
    private final ModeRouterPromptBuilder inputBuilder;
    public ContinuationIntentRouter(LlmClient client, PromptRepository prompts) {
        this.client = Objects.requireNonNull(client); this.prompts = Objects.requireNonNull(prompts);
        this.inputBuilder = new ModeRouterPromptBuilder(prompts);
    }

    public ContinuationIntent route(RuntimeExecution request, List<SessionProjection.ConversationNode> history,
            RecoveryContextSnapshot snapshot, RecoveryContextBudget budget) {
        try {
            if (CancellationContext.isCancelled() || Thread.currentThread().isInterrupted()) return ContinuationIntent.clarify();
            if (!request.workspaceKey().value().equals(snapshot.workspaceKey()) || !request.sessionId().equals(snapshot.parentSessionId()))
                return ContinuationIntent.clarify();
            var formatted = new RecoveryContextFormatter().formatForParent(snapshot, budget, request.id());
            var data = inputBuilder.build(history, request.submittedInput(), formatted).get(1);
            var messages = List.of(LlmClient.Message.system(prompts.loadRequired("modes/continuation-intent.md")), data);
            var result = new StructuredJsonExecutor().execute(client, messages, null,
                    new StructuredOutputSpec("continuation_intent", schema(), true), false,
                    root -> decode(root, snapshot, request.id()), LlmClient.StreamListener.NO_OP);
            if (CancellationContext.isCancelled() || Thread.currentThread().isInterrupted()
                    || result.response() != null && result.response().toolCalls() != null && !result.response().toolCalls().isEmpty())
                return ContinuationIntent.clarify();
            return result.value();
        } catch (IOException | RuntimeException failure) { return ContinuationIntent.clarify(); }
    }

    private static ContinuationIntent decode(JsonNode root, RecoveryContextSnapshot snapshot, String requestId) throws IOException {
        if (root == null || !root.isObject() || root.size() != 4 || !root.has("intent") || !root.get("intent").isTextual()
                || !root.has("newTurnKind") || !root.has("targetExecutionId") || !root.has("targetGeneration"))
            throw new IOException("Expected exact continuation intent object");
        String kind = root.get("intent").textValue();
        var subtype = root.get("newTurnKind"); var target = root.get("targetExecutionId"); var generation = root.get("targetGeneration");
        if ("CONTINUE".equals(kind)) {
            if (!subtype.isNull() || !target.isTextual() || !generation.isIntegralNumber() || !generation.canConvertToInt() || generation.intValue() < 0)
                throw new IOException("Invalid continuation target tuple");
            long matches = snapshot.executions().stream().filter(e -> e.continuationCandidate() && !requestId.equals(e.executionId())
                    && e.executionId().equals(target.textValue()) && e.recoveryGeneration() == generation.intValue()).count();
            if (matches != 1) throw new IOException("Target is not one exact local held candidate");
            return new ContinuationIntent(ContinuationIntent.Kind.CONTINUE, RequestSemantics.CONTINUE, target.textValue(), generation.intValue());
        }
        if (!target.isNull() || !generation.isNull()) throw new IOException("Only CONTINUE has a target");
        if ("CLARIFY".equals(kind) && subtype.isNull()) return ContinuationIntent.clarify();
        if ("NEW_TURN".equals(kind) && subtype.isTextual()) {
            if ("STATUS_QUESTION".equals(subtype.textValue())) return new ContinuationIntent(ContinuationIntent.Kind.NEW_TURN, RequestSemantics.STATUS_QUESTION, null, null);
            if ("NEW_REQUEST".equals(subtype.textValue())) return new ContinuationIntent(ContinuationIntent.Kind.NEW_TURN, RequestSemantics.NEW_REQUEST, null, null);
        }
        throw new IOException("Unknown or conflicting intent");
    }

    private static JsonNode schema() {
        var root = JSON.createObjectNode(); root.put("type", "object"); root.put("additionalProperties", false);
        root.putArray("required").add("intent").add("newTurnKind").add("targetExecutionId").add("targetGeneration");
        var p = root.putObject("properties");
        p.putObject("intent").put("type", "string").putArray("enum").add("NEW_TURN").add("CONTINUE").add("CLARIFY");
        var subtype = p.putObject("newTurnKind"); subtype.putArray("type").add("string").add("null");
        subtype.putArray("enum").add("STATUS_QUESTION").add("NEW_REQUEST").addNull();
        p.putObject("targetExecutionId").putArray("type").add("string").add("null");
        p.putObject("targetGeneration").putArray("type").add("integer").add("null");
        return root;
    }
}
