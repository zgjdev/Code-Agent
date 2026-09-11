package com.codeagent.context;

import java.time.Instant;

/** Keeps one successful usage anchor; it deliberately does not own conversation messages. */
public final class ContextTokenTracker {
    public record ContextAnchor(
            RequestSnapshot snapshot,
            long anchorSurfaceTokens,
            MeasuredUsage usage,
            boolean usageTrusted,
            Instant measuredAt) {}

    public enum Mode { NONE, FULL_ESTIMATE, USAGE_ANCHORED_DELTA }

    public record ContextPrediction(
            long rawPredictedTokens,
            long effectiveTokens,
            long fullEstimateTokens,
            long surfaceDeltaTokens,
            long safetyMarginTokens,
            Mode mode,
            boolean safeForCompaction,
            String fallbackReason) {}

    private final long safetyMarginTokens;
    private ContextAnchor anchor;
    private InvalidationReason lastInvalidation = InvalidationReason.INITIAL;

    public ContextTokenTracker() {
        this(256L);
    }

    public ContextTokenTracker(long safetyMarginTokens) {
        this.safetyMarginTokens = Math.max(0L, safetyMarginTokens);
    }

    public ContextPrediction predict(RequestSnapshot current) {
        if (current == null) {
            return new ContextPrediction(0, safetyMarginTokens, 0, 0,
                    safetyMarginTokens, Mode.NONE, false, "null snapshot");
        }
        long full = current.estimatedRequestTokens();
        if (!hasUsableAnchor(current)) {
            return prediction(full, full, 0, Mode.FULL_ESTIMATE, fallbackReason(current));
        }
        long delta = (long) current.estimatedSurfaceTokens() - anchor.anchorSurfaceTokens();
        long raw = saturatingAdd(anchor.usage().usageAnchorTokens(), delta);
        raw = Math.max(0L, raw);
        return prediction(raw, full, delta, Mode.USAGE_ANCHORED_DELTA, null);
    }

    public void recordSuccessfulCall(
            RequestSnapshot request,
            int assistantSurfaceEstimateTokens,
            MeasuredUsage usage) {
        if (request == null || usage == null || !usage.trusted()
                || usage.inputScope() == MeasuredUsage.InputScope.UNKNOWN
                || !usage.includesSystem() || !usage.includesTools()) {
            invalidate(InvalidationReason.USAGE_UNTRUSTED);
            return;
        }
        long surface = saturatingAdd(request.estimatedSurfaceTokens(),
                Math.max(0, assistantSurfaceEstimateTokens));
        this.anchor = new ContextAnchor(request, surface, usage, true, Instant.now());
        this.lastInvalidation = null;
    }

    public void invalidate(InvalidationReason reason) {
        anchor = null;
        lastInvalidation = reason == null ? InvalidationReason.USAGE_UNTRUSTED : reason;
    }

    public boolean hasUsableAnchor(RequestSnapshot current) {
        if (anchor == null || current == null || !anchor.usageTrusted()
                || anchor.snapshot().containsUnsupportedPart()
                || current.containsUnsupportedPart()
                || !sameCanonicalEnvelope(anchor.snapshot(), current)) return false;
        long localAnchor = anchor.anchorSurfaceTokens()
                + Math.max(0, anchor.snapshot().estimatedToolTokens());
        return anchor.usage().usageAnchorTokens() >= localAnchor;
    }

    private ContextPrediction prediction(long raw, long full, long delta,
                                         Mode mode, String fallbackReason) {
        long effective = saturatingAdd(raw, safetyMarginTokens);
        return new ContextPrediction(raw, effective, full, delta,
                safetyMarginTokens, mode, mode != Mode.NONE, fallbackReason);
    }

    private String fallbackReason(RequestSnapshot current) {
        if (anchor == null) return "no anchor";
        if (!sameCanonicalEnvelope(anchor.snapshot(), current)) return "envelope changed";
        if (!anchor.usageTrusted() || !anchor.usage().trusted()) return "usage untrusted";
        return "usage below local anchor estimate";
    }

    private static boolean sameCanonicalEnvelope(RequestSnapshot a, RequestSnapshot b) {
        return equals(a.provider(), b.provider())
                && equals(a.model(), b.model())
                && equals(a.callConfigFingerprint(), b.callConfigFingerprint())
                && equals(a.toolSchemaFingerprint(), b.toolSchemaFingerprint());
    }

    private static boolean equals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static long saturatingAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        if (b < 0 && a < Long.MIN_VALUE - b) return Long.MIN_VALUE;
        return a + b;
    }
}
