package com.codeagent.tool;

import com.codeagent.llm.LlmClient;

import java.util.Collection;
import java.util.List;
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
                         com.fasterxml.jackson.databind.JsonNode structuredContent) {
    public enum FailureKind {
        NONE,
        INVALID_CONFIGURATION,
        BACKEND_UNAVAILABLE,
        TOOL_NOT_FOUND,
        POLICY_DENIED,
        HITL_REJECTED,
        CANCELLED,
        EXECUTION_ERROR
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
