package com.codeagent.context;

import com.codeagent.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.codeagent.context.HistorySnipperTest.batch;
import static com.codeagent.context.HistorySnipperTest.options;
import static com.codeagent.context.HistorySnipperTest.snapshot;
import static org.junit.jupiter.api.Assertions.*;

class ContextWindowManagerTest {
    @Test
    void commitsSeveralMicroEditsTogetherAndReturnsExactlyTheProjectedRequest() throws Exception {
        var nodes = new ArrayList<ContextNode>();
        nodes.addAll(batch(1, "one", "exec", "read_file", "{\"path\":\"a\"}", "large ".repeat(900)));
        nodes.addAll(batch(3, "two", "exec", "read_file", "{\"path\":\"b\"}", "large ".repeat(900)));
        nodes.addAll(batch(5, "three", "exec", "read_file", "{}", "recent"));
        nodes.addAll(batch(7, "four", "exec", "read_file", "{}", "latest"));
        var current = new AtomicReference<>(snapshot(nodes, List.of()));
        var edits = new ArrayList<ContextEditBatch>();
        var result = new ContextWindowManager().prepare(null, List.of(), RequestPurpose.NORMAL, false,
                options(), new ContextTokenTracker(0), current::get, commit -> {
                    edits.add(commit);
                    current.set(new ContextWindowState.Snapshot("scope", nodes.size(), 1,
                            nodes, commit.edits(), current.get().protection()));
                    return ContextEditSink.CommitResult.APPLIED;
                }, ContextWindowManager.SummaryLayers.NONE);
        assertEquals(1, edits.size());
        assertEquals(2, edits.get(0).edits().size());
        assertEquals("one", result.messages().get(1).toolCallId());
        assertEquals("two", result.messages().get(3).toolCallId());
        assertEquals("recent", result.messages().get(5).content());
        assertEquals("latest", result.messages().get(7).content());
        assertEquals(new RequestSnapshotFactory().capture(null, result.messages(), result.tools(),
                result.snapshot().historyVersion()), result.snapshot());
    }

    @Test
    void previewAndReviewAvoidAllWritesAndSummaryCalls() throws Exception {
        var nodes = List.of(new ContextNode(NodeId.raw("scope", 1),
                LlmClient.Message.system("required ".repeat(3000)), SourceKind.SYSTEM, "exec", null, null, null));
        var current = snapshot(nodes, List.of());
        var manager = new ContextWindowManager();
        var summaries = new ContextWindowManager.SummaryLayers() {
            @Override
            public boolean auto(ContextWindowState.Snapshot snapshot, List<ContextNode> view) {
                throw new AssertionError("unexpected summary");
            }
        };
        for (var purpose : List.of(RequestPurpose.PREVIEW, RequestPurpose.REVIEW)) {
            var result = manager.prepare(null, List.of(), purpose, false, options(), new ContextTokenTracker(0),
                    () -> current, commit -> { throw new AssertionError("unexpected commit"); }, summaries);
            assertEquals(nodes.get(0).message(), result.messages().get(0));
            assertFalse(result.sendable());
            assertTrue(result.protectedTokens() > options().triggerTokens());
        }
    }

    @Test
    void previewUsesFullEstimateForBypassedOverlayWithoutChangingTheUsageAnchor() throws Exception {
        var nodes = new ArrayList<ContextNode>();
        nodes.addAll(batch(1, "one", "exec", "read_file", "{}", "large ".repeat(900)));
        nodes.addAll(batch(3, "two", "exec", "read_file", "{}", "recent"));
        nodes.addAll(batch(5, "three", "exec", "read_file", "{}", "latest"));
        var edit = new ToolResultMicrocompactor().propose(snapshot(nodes, List.of()), options()).orElseThrow();
        var applied = snapshot(nodes, List.of(edit));
        var manager = new ContextWindowManager();
        var tracker = new ContextTokenTracker(0);
        var prior = manager.prepare(null, List.of(), RequestPurpose.STATUS, false, options(), tracker,
                () -> applied, null, ContextWindowManager.SummaryLayers.NONE);
        tracker.recordSuccessfulCall(prior.snapshot(), 0, new MeasuredUsage(5000, 0, 0,
                MeasuredUsage.InputScope.TOTAL_PROMPT, true, true, true, Instant.now()));
        var pin = new ProtectionSet(Set.of(nodes.get(1).id()), Set.of(), Set.of(), Set.of(), Set.of());
        var bypassed = new ContextWindowState.Snapshot(applied.scopeId(), applied.sourceRevision(),
                applied.contextGeneration(), nodes, List.of(edit), ProtectionSet.derive(nodes, pin, 2));
        var preview = manager.prepare(null, List.of(), RequestPurpose.PREVIEW, false, options(), tracker,
                () -> bypassed, null, ContextWindowManager.SummaryLayers.NONE);
        assertEquals(nodes.get(1).message(), preview.messages().get(1));
        assertEquals(ContextTokenTracker.Mode.FULL_ESTIMATE, preview.prediction().mode());
        assertTrue(tracker.hasUsableAnchor(prior.snapshot()));
        manager.prepare(null, List.of(), RequestPurpose.STATUS, false, options(), tracker,
                () -> bypassed, null, ContextWindowManager.SummaryLayers.NONE);
        assertFalse(tracker.hasUsableAnchor(prior.snapshot()));
    }

    @Test
    void overflowForcesSummaryLayersBelowTheirNormalThresholds() throws Exception {
        var current = snapshot(List.of(), List.of());
        var calls = new AtomicInteger();
        var summaries = new ContextWindowManager.SummaryLayers() {
            @Override
            public java.util.Optional<ContextEdit> collapse(ContextWindowState.Snapshot source,
                                                            List<ContextNode> view) {
                assertEquals(0, calls.getAndIncrement());
                return java.util.Optional.empty();
            }

            @Override
            public boolean auto(ContextWindowState.Snapshot source, List<ContextNode> view) {
                assertEquals(1, calls.getAndIncrement());
                return false;
            }
        };
        var result = new ContextWindowManager().prepare(null, List.of(), RequestPurpose.RECOVERY, true,
                options(), new ContextTokenTracker(0), () -> current, null, summaries);
        assertEquals(2, calls.get());
        assertTrue(result.sendable());
    }
}
