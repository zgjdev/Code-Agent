package com.codeagent.context;

import java.time.Instant;

public record MeasuredUsage(
        int inputTokens,
        int outputTokens,
        int cachedInputTokens,
        InputScope inputScope,
        boolean includesTools,
        boolean includesSystem,
        boolean trusted,
        Instant measuredAt) {

    public enum InputScope { TOTAL_PROMPT, UNCACHED_PROMPT, UNKNOWN }

    public long promptPressureTokens() {
        if (inputScope == InputScope.TOTAL_PROMPT) return Math.max(0L, inputTokens);
        if (inputScope == InputScope.UNCACHED_PROMPT) {
            return Math.max(0L, (long) inputTokens + Math.max(0, cachedInputTokens));
        }
        return 0L;
    }

    public long usageAnchorTokens() {
        return Math.max(0L, promptPressureTokens() + Math.max(0, outputTokens));
    }
}
