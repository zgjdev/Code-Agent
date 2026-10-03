package com.codeagent.agent;

import com.codeagent.context.MeasuredUsage;
import com.codeagent.history.SessionProjection;
import com.codeagent.llm.LlmClient;
import com.codeagent.llm.StructuredJsonExecutor;
import com.codeagent.llm.StructuredOutputException;
import com.codeagent.llm.StructuredOutputSpec;
import com.codeagent.prompt.ModeRouterPromptBuilder;
import com.codeagent.runtime.CancellationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/** Selects one execution mode without tools or Parent Session writes. */
public final class ExecutionModeRouter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final StructuredOutputSpec ROUTING_OUTPUT =
            new StructuredOutputSpec("execution_mode", routingSchema(), true);

    private final LlmClient llmClient;
    private final StructuredJsonExecutor structuredJsonExecutor = new StructuredJsonExecutor();
    private final ModeRouterPromptBuilder promptBuilder;

    public ExecutionModeRouter(LlmClient llmClient, ModeRouterPromptBuilder promptBuilder) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
    }

    public RoutingDecision route(String submittedInput,
                                 List<SessionProjection.ConversationNode> history) {
        ensureNotCancelled();
        LlmClient.ChatResponse response = null;
        try {
            StructuredJsonExecutor.Result<ExecutionMode> result = structuredJsonExecutor.execute(
                    llmClient,
                    promptBuilder.build(history, submittedInput),
                    null,
                    ROUTING_OUTPUT,
                    false,
                    ExecutionModeRouter::parseMode,
                    LlmClient.StreamListener.NO_OP);
            response = result.response();
            ensureNotCancelled();
            MeasuredUsage usage = llmClient.normalizeUsage(response);
            return new RoutingDecision(result.value(), RoutingSource.AUTO_MODEL, Optional.of(usage));
        } catch (CancellationException e) {
            throw e;
        } catch (StructuredOutputException e) {
            ensureNotCancelled();
            response = e.response();
            Optional<MeasuredUsage> usage = safeNormalizeUsage(response);
            return new RoutingDecision(ExecutionMode.REACT, RoutingSource.AUTO_FALLBACK, usage);
        } catch (IOException | RuntimeException e) {
            ensureNotCancelled();
            Optional<MeasuredUsage> usage = safeNormalizeUsage(response);
            return new RoutingDecision(ExecutionMode.REACT, RoutingSource.AUTO_FALLBACK, usage);
        }
    }

    private Optional<MeasuredUsage> safeNormalizeUsage(LlmClient.ChatResponse response) {
        if (response == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(llmClient.normalizeUsage(response));
        } catch (RuntimeException ignored) {
            ensureNotCancelled();
            return Optional.empty();
        }
    }

    private static ExecutionMode parseMode(JsonNode root) throws IOException {
        if (root == null || !root.isObject() || root.size() != 1
                || !root.has("mode") || !root.get("mode").isTextual()) {
            throw new IOException("Mode router response must be a single-field JSON object");
        }
        return switch (root.get("mode").textValue()) {
            case "react" -> ExecutionMode.REACT;
            case "plan" -> ExecutionMode.PLAN;
            default -> throw new IOException("Unknown execution mode");
        };
    }

    private static JsonNode routingSchema() {
        var root = JSON.createObjectNode();
        root.put("type", "object");
        var properties = root.putObject("properties");
        var mode = properties.putObject("mode");
        mode.put("type", "string");
        mode.putArray("enum").add("react").add("plan");
        root.putArray("required").add("mode");
        root.put("additionalProperties", false);
        return root;
    }

    private static void ensureNotCancelled() {
        if (CancellationContext.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Mode routing cancelled");
        }
    }
}
