package com.codeagent.context;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ContextTokenTrackerTest {
    private RequestSnapshot snapshot(String surface, String tools, long version) {
        return new RequestSnapshot(
                "test", "model", "call", tools, surface, "system",
                1_000, 200, 2, 0, version, false);
    }

    private RequestSnapshot snapshotWithSurfaceTokens(int surfaceTokens, String tools, long version) {
        return new RequestSnapshot(
                "test", "model", "call", tools, "surface", "system",
                surfaceTokens, 200, 2, 0, version, false);
    }

    @Test
    void noAnchorUsesFullEstimate() {
        ContextTokenTracker tracker = new ContextTokenTracker();

        ContextTokenTracker.ContextPrediction prediction = tracker.predict(snapshot("s1", "t1", 1));

        assertEquals(ContextTokenTracker.Mode.FULL_ESTIMATE, prediction.mode());
        assertEquals(1_200, prediction.fullEstimateTokens());
        assertEquals(1_200, prediction.rawPredictedTokens());
    }

    @Test
    void trustedAnchorUsesSignedSurfaceDelta() {
        ContextTokenTracker tracker = new ContextTokenTracker();
        RequestSnapshot anchor = snapshot("s1", "t1", 1);
        tracker.recordSuccessfulCall(anchor, 100,
                new MeasuredUsage(1_200, 100, 0,
                        MeasuredUsage.InputScope.TOTAL_PROMPT,
                        true, true, true, Instant.now()));

        ContextTokenTracker.ContextPrediction prediction = tracker.predict(snapshotWithSurfaceTokens(1_200, "t1", 2));

        assertEquals(ContextTokenTracker.Mode.USAGE_ANCHORED_DELTA, prediction.mode());
        assertEquals(100, prediction.surfaceDeltaTokens());
        assertEquals(1_400, prediction.rawPredictedTokens());
    }

    @Test
    void changedToolSchemaFallsBackToFullEstimate() {
        ContextTokenTracker tracker = new ContextTokenTracker();
        tracker.recordSuccessfulCall(snapshot("s1", "t1", 1), 100,
                new MeasuredUsage(1_200, 100, 0,
                        MeasuredUsage.InputScope.TOTAL_PROMPT,
                        true, true, true, Instant.now()));

        ContextTokenTracker.ContextPrediction prediction = tracker.predict(snapshot("s2", "t2", 2));

        assertEquals(ContextTokenTracker.Mode.FULL_ESTIMATE, prediction.mode());
    }

    @Test
    void invalidationDisablesAnchor() {
        ContextTokenTracker tracker = new ContextTokenTracker();
        tracker.recordSuccessfulCall(snapshot("s1", "t1", 1), 100,
                new MeasuredUsage(1_200, 100, 0,
                        MeasuredUsage.InputScope.TOTAL_PROMPT,
                        true, true, true, Instant.now()));

        tracker.invalidate(InvalidationReason.COMPACTION);

        assertFalse(tracker.hasUsableAnchor(snapshot("s2", "t1", 2)));
    }
}
