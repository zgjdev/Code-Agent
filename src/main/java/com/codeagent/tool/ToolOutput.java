package com.codeagent.tool;

import com.codeagent.context.ToolResultDescriptor.CaptureCompleteness;
import com.codeagent.context.ToolResultDescriptor.OutcomeSource;
import com.codeagent.llm.LlmClient;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Typed result returned by a tool implementation.
 *
 * <p>{@code discoveredUrls} is deliberately separate from the human-readable
 * text. Only the stable web_search facade and its validated AnySearch/Step result
 * adapter may populate it; generic callers must never recover URL
 * authority by scraping {@link #text()}.</p>
 */
public record ToolOutput(String text,
                         List<LlmClient.ContentPart> imageParts,
                         boolean successful,
                         List<String> discoveredUrls,
                         FailureKind failureKind,
                         com.fasterxml.jackson.databind.JsonNode structuredContent,
                         Facts facts) {
    /** Observations collected in the executor, outside its display text or remote structured payload. */
    public record Facts(OutcomeSource outcomeSource, CaptureCompleteness captureCompleteness,
                        String executedCommand, Integer exitCode, boolean observationUnknown,
                        Map<String, String> resourceLocation, Integer resultCount) {
        public Facts {
            Objects.requireNonNull(outcomeSource);
            Objects.requireNonNull(captureCompleteness);
            resourceLocation = Map.copyOf(resourceLocation);
        }

        static Facts typed() {
            return new Facts(OutcomeSource.TYPED, CaptureCompleteness.UNKNOWN, null, null, false, Map.of(), null);
        }
    }

    public enum FailureKind {
        NONE,
        INVALID_CONFIGURATION,
        BACKEND_UNAVAILABLE,
        TOOL_NOT_FOUND,
        POLICY_DENIED,
        HITL_REJECTED,
        CANCELLED,
        TIMED_OUT,
        EXECUTION_ERROR
    }

    public ToolOutput(String text, List<LlmClient.ContentPart> imageParts, boolean successful,
                      List<String> discoveredUrls, FailureKind failureKind,
                      com.fasterxml.jackson.databind.JsonNode structuredContent) {
        this(text, imageParts, successful, discoveredUrls, failureKind, structuredContent, Facts.typed());
    }

    public ToolOutput(String text, List<LlmClient.ContentPart> imageParts, boolean successful,
                      List<String> discoveredUrls, FailureKind failureKind) {
        this(text, imageParts, successful, discoveredUrls, failureKind, null);
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode structuredContent() {
        return structuredContent == null ? null : structuredContent.deepCopy();
    }

    public ToolOutput {
        structuredContent = structuredContent == null ? null : structuredContent.deepCopy();
        text = text == null ? "" : text;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
        discoveredUrls = discoveredUrls == null
                ? List.of()
                : discoveredUrls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(url -> !url.isEmpty())
                .distinct()
                .toList();
        failureKind = failureKind == null
                ? (successful ? FailureKind.NONE : FailureKind.EXECUTION_ERROR)
                : failureKind;
        Objects.requireNonNull(facts);
    }

    /** Backward-compatible constructor for callers without typed failure metadata. */
    public ToolOutput(String text,
                      List<LlmClient.ContentPart> imageParts,
                      boolean successful,
                      List<String> discoveredUrls) {
        this(text, imageParts, successful, discoveredUrls,
                successful ? FailureKind.NONE : FailureKind.EXECUTION_ERROR);
    }

    /** Backward-compatible constructor for successful text/image tools. */
    public ToolOutput(String text, List<LlmClient.ContentPart> imageParts) {
        this(text, imageParts, true, List.of(), FailureKind.NONE);
    }

    public static ToolOutput text(String text) {
        return new ToolOutput(text, List.of(), true, List.of(), FailureKind.NONE);
    }

    public static ToolOutput legacy(String text, boolean successful) {
        return new ToolOutput(text, List.of(), successful, List.of(),
                successful ? FailureKind.NONE : FailureKind.EXECUTION_ERROR, null,
                new Facts(OutcomeSource.LEGACY_INFERRED, CaptureCompleteness.UNKNOWN,
                        null, null, false, Map.of(), null));
    }

    ToolOutput withCapture(CaptureCompleteness capture, Map<String, String> resourceLocation, Integer count) {
        if (facts.outcomeSource() != OutcomeSource.TYPED) return this;
        return new ToolOutput(text, imageParts, successful, discoveredUrls, failureKind, structuredContent,
                new Facts(facts.outcomeSource(), capture, facts.executedCommand(), facts.exitCode(),
                        facts.observationUnknown(), resourceLocation, count));
    }

    ToolOutput withCommand(String executedCommand, Integer exitCode, CaptureCompleteness capture,
                           boolean observationUnknown) {
        return new ToolOutput(text, imageParts, successful, discoveredUrls, failureKind, structuredContent,
                new Facts(facts.outcomeSource(), capture, executedCommand, exitCode, observationUnknown, Map.of(), null));
    }

    public static ToolOutput failure(String text) {
        return failure(FailureKind.EXECUTION_ERROR, text);
    }

    public static ToolOutput failure(String text, List<LlmClient.ContentPart> imageParts) {
        return failure(FailureKind.EXECUTION_ERROR, text, imageParts);
    }

    public static ToolOutput failure(FailureKind kind, String text) {
        return failure(kind, text, List.of());
    }

    public static ToolOutput failure(FailureKind kind, String text,
                                     List<LlmClient.ContentPart> imageParts) {
        FailureKind effective = kind == null || kind == FailureKind.NONE
                ? FailureKind.EXECUTION_ERROR
                : kind;
        return new ToolOutput(text, imageParts, false, List.of(), effective);
    }

    public static ToolOutput discovered(String text, Collection<String> discoveredUrls) {
        return new ToolOutput(text, List.of(), true,
                discoveredUrls == null ? List.of() : List.copyOf(discoveredUrls), FailureKind.NONE);
    }

    public boolean hasImageParts() {
        return !imageParts.isEmpty();
    }
}
