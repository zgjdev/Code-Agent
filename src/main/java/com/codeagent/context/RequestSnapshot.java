package com.codeagent.context;

public record RequestSnapshot(
        String provider,
        String model,
        String callConfigFingerprint,
        String toolSchemaFingerprint,
        String surfaceFingerprint,
        String systemPromptFingerprint,
        int estimatedSurfaceTokens,
        int estimatedToolTokens,
        int messageCount,
        int imageCount,
        long historyVersion,
        boolean containsUnsupportedPart) {

    public long estimatedRequestTokens() {
        return Math.max(0L, (long) estimatedSurfaceTokens + estimatedToolTokens);
    }
}
