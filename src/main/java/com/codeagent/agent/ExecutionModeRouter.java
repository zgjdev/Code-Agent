package com.codeagent.agent;

import com.codeagent.context.MeasuredUsage;
import com.codeagent.history.SessionProjection;
import com.codeagent.llm.LlmClient;
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

    private final LlmClient llmClient;
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
            response = llmClient.chat(promptBuilder.build(history, submittedInput), null);
            ensureNotCancelled();
            ExecutionMode mode = parseMode(response == null ? null : response.content());
            MeasuredUsage usage = llmClient.normalizeUsage(response);
            return new RoutingDecision(mode, RoutingSource.AUTO_MODEL, Optional.of(usage));
        } catch (CancellationException e) {
            throw e;
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

    private static ExecutionMode parseMode(String content) throws IOException {
        if (content == null || content.isBlank()) {
            throw new IOException("Mode router returned empty content");
        }
        JsonNode root = JSON.readTree(content.trim());
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

    private static void ensureNotCancelled() {
        if (CancellationContext.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Mode routing cancelled");
        }
    }
}
