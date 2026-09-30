package com.codeagent.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Executes one structured JSON operation with deterministic local decoding and
 * one bounded repair retry. Invalid model output never becomes trusted business data.
 */
public final class StructuredJsonExecutor {
    private static final Logger log = LoggerFactory.getLogger(StructuredJsonExecutor.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int DEFAULT_MAX_ATTEMPTS = 2;
    private static final int MAX_REPAIR_OUTPUT_CHARS = 4_000;
    private static final int MAX_ERROR_CHARS = 500;

    private final int maxAttempts;

    public StructuredJsonExecutor() {
        this(DEFAULT_MAX_ATTEMPTS);
    }

    StructuredJsonExecutor(int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 3");
        }
        this.maxAttempts = maxAttempts;
    }

    @FunctionalInterface
    public interface JsonDecoder<T> {
        T decode(JsonNode root) throws IOException;
    }

    public record Result<T>(LlmClient.ChatResponse response, JsonNode json, T value) {
    }

    public <T> Result<T> execute(LlmClient client,
                                 List<LlmClient.Message> messages,
                                 List<LlmClient.Tool> tools,
                                 StructuredOutputSpec spec,
                                 boolean allowMarkdownFence,
                                 JsonDecoder<T> decoder,
                                 LlmClient.StreamListener listener) throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(decoder, "decoder");

        List<LlmClient.Message> attemptMessages = new ArrayList<>(messages);
        LlmClient.StreamListener safeListener = reasoningOnly(listener);
        int totalInput = 0;
        int totalOutput = 0;
        int totalCached = 0;
        LlmClient.ChatResponse lastAggregated = null;
        IOException lastValidationFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            LlmClient.ChatResponse response = client.chatStructured(
                    List.copyOf(attemptMessages), tools, spec, safeListener);
            totalInput += Math.max(0, response == null ? 0 : response.inputTokens());
            totalOutput += Math.max(0, response == null ? 0 : response.outputTokens());
            totalCached += Math.max(0, response == null ? 0 : response.cachedInputTokens());
            lastAggregated = aggregate(response, totalInput, totalOutput, totalCached);

            try {
                JsonNode root = parseJson(response == null ? null : response.content(), allowMarkdownFence);
                T value = decoder.decode(root);
                return new Result<>(lastAggregated, root, value);
            } catch (IOException validationFailure) {
                lastValidationFailure = validationFailure;
                if (attempt >= maxAttempts) {
                    break;
                }
                log.warn("Structured output validation failed; requesting one repair "
                                + "provider={} model={} contract={} attempt={}/{} cause={}",
                        client.getProviderName(), client.getModelName(), spec.name(),
                        attempt, maxAttempts, validationFailure.getMessage());
                attemptMessages.add(LlmClient.Message.assistant(
                        truncate(response == null ? "" : response.content(), MAX_REPAIR_OUTPUT_CHARS)));
                attemptMessages.add(LlmClient.Message.user(repairInstruction(spec, validationFailure)));
            }
        }

        throw new StructuredOutputException(
                "Structured output contract '" + spec.name() + "' remained invalid after "
                        + maxAttempts + " attempt(s)",
                lastValidationFailure,
                lastAggregated);
    }

    private static JsonNode parseJson(String content, boolean allowMarkdownFence) throws IOException {
        if (content == null || content.isBlank()) {
            throw new IOException("LLM returned empty structured output");
        }
        String candidate = content.trim();
        if (allowMarkdownFence) {
            candidate = stripOuterJsonFence(candidate);
        }
        JsonNode root = JSON.readTree(candidate);
        if (root == null) {
            throw new IOException("LLM returned empty JSON document");
        }
        return root;
    }

    private static String stripOuterJsonFence(String content) {
        if (!content.startsWith("```") || !content.endsWith("```")) {
            return content;
        }
        int firstNewline = content.indexOf('\n');
        if (firstNewline < 0) {
            return content;
        }
        String header = content.substring(0, firstNewline).trim();
        if (!"```".equals(header) && !"```json".equalsIgnoreCase(header)) {
            return content;
        }
        return content.substring(firstNewline + 1, content.length() - 3).trim();
    }

    private static String repairInstruction(StructuredOutputSpec spec, IOException failure) {
        String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return "上一响应不符合 JSON contract '" + spec.name() + "'。错误："
                + truncate(detail, MAX_ERROR_CHARS)
                + "\n只返回修正后的 JSON，不要使用 Markdown 代码块，不要解释，不要增加 schema 之外的字段。";
    }

    private static LlmClient.ChatResponse aggregate(LlmClient.ChatResponse response,
                                                    int inputTokens,
                                                    int outputTokens,
                                                    int cachedTokens) {
        if (response == null) {
            return null;
        }
        return new LlmClient.ChatResponse(
                response.role(),
                response.content(),
                response.reasoningContent(),
                response.toolCalls(),
                inputTokens,
                outputTokens,
                cachedTokens);
    }

    private static LlmClient.StreamListener reasoningOnly(LlmClient.StreamListener listener) {
        if (listener == null || listener == LlmClient.StreamListener.NO_OP) {
            return LlmClient.StreamListener.NO_OP;
        }
        return new LlmClient.StreamListener() {
            @Override
            public void onReasoningDelta(String delta) {
                listener.onReasoningDelta(delta);
            }
        };
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }
}
