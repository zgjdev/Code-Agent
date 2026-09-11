package com.codeagent.context;

import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class RequestSnapshotFactory {
    private static final ObjectMapper JSON = new ObjectMapper();

    public RequestSnapshot capture(
            LlmClient client,
            List<LlmClient.Message> messages,
            List<LlmClient.Tool> tools,
            long historyVersion) {
        List<LlmClient.Message> safeMessages = messages == null ? List.of() : List.copyOf(messages);
        List<LlmClient.Tool> safeTools = tools == null ? List.of() : List.copyOf(tools);
        String surfaceJson = canonical(safeMessages);
        String toolsJson = canonical(safeTools);
        String systemJson = canonical(safeMessages.stream()
                .filter(m -> m != null && "system".equals(m.role()))
                .findFirst().map(List::of).orElse(List.of()));
        return new RequestSnapshot(
                client == null ? null : client.getProviderName(),
                client == null ? null : client.getModelName(),
                client == null ? "" : client.requestConfigurationFingerprint(),
                sha256(toolsJson),
                sha256(surfaceJson),
                sha256(systemJson),
                estimateSurface(safeMessages),
                com.codeagent.memory.TokenBudget.estimateToolsTokens(safeTools),
                safeMessages.size(),
                safeMessages.stream().mapToInt(m -> m == null ? 0 : m.imagePartCount()).sum(),
                historyVersion,
                containsUnsupportedPart(safeMessages));
    }

    private static int estimateSurface(List<LlmClient.Message> messages) {
        return com.codeagent.memory.TokenBudget.estimateMessagesTokens(messages);
    }

    private static boolean containsUnsupportedPart(List<LlmClient.Message> messages) {
        return messages.stream().filter(m -> m != null && m.contentParts() != null)
                .flatMap(m -> m.contentParts().stream())
                .anyMatch(p -> p != null && !p.isText() && !p.isImage());
    }

    private static String canonical(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法规范化请求快照", e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
