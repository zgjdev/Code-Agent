package com.codeagent.context;

import com.codeagent.llm.LlmClient;
import com.codeagent.llm.LlmClient.Message;
import com.codeagent.llm.LlmClient.Tool;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.codeagent.context.ContextWindowState.Options;
import static com.codeagent.context.ContextWindowState.Snapshot;

/** Prepares the exact projected request and publishes edits only through the supplied commit boundary. */
public final class ContextWindowManager {
    private static final int MAX_BATCH_BYTES = 1024 * 1024;

    public interface SummaryLayers {
        SummaryLayers NONE = new SummaryLayers() {};

        default Optional<ContextEdit> collapse(Snapshot snapshot, List<ContextNode> currentView) {
            return Optional.empty();
        }

        default boolean auto(Snapshot snapshot, List<ContextNode> currentView) throws IOException {
            return false;
        }
    }

    public enum Reason { READY, PROTECTED_CONTENT_EXCEEDS_BUDGET, NO_ELIGIBLE_REDUCTION }

    public record Result(List<Message> messages, List<Tool> tools, RequestSnapshot snapshot,
                         ContextTokenTracker.ContextPrediction prediction, boolean sendable, Reason reason,
                         long protectedTokens, long ineligibleTokens) {}

    private record View(Snapshot source, List<ContextNode> nodes, List<Message> messages, RequestSnapshot request,
                        ContextTokenTracker.ContextPrediction prediction, long protectedTokens, long fixedTokens) {
        long effectiveTokens() { return prediction.effectiveTokens(); }
    }

    private final RequestSnapshotFactory snapshots = new RequestSnapshotFactory();

    public Result prepare(LlmClient client, List<Tool> tools, RequestPurpose purpose, boolean overflow,
                          Options options, ContextTokenTracker tracker, Supplier<Snapshot> refresh,
                          ContextEditSink sink, SummaryLayers summaries) throws IOException {
        return prepare(client, tools, purpose, overflow, options, tracker, refresh, sink, summaries, Function.identity());
    }

    /** Composes request-only nodes after durable projection, without exposing them as editable sources. */
    public Result prepare(LlmClient client, List<Tool> tools, RequestPurpose purpose, boolean overflow,
                          Options options, ContextTokenTracker tracker, Supplier<Snapshot> refresh,
                          ContextEditSink sink, SummaryLayers summaries,
                          Function<List<ContextNode>, List<ContextNode>> sendView) throws IOException {
        List<Tool> fixedTools = tools == null ? List.of() : tools.stream()
                .map(tool -> new Tool(tool.name(), tool.description(),
                        tool.parameters() == null ? null : tool.parameters().deepCopy()))
                .toList();
        boolean publishPrediction = purpose != RequestPurpose.PREVIEW;
        View view = capture(client, fixedTools, tracker, refresh.get(), publishPrediction, overflow, sendView);
        boolean allowEdits = options.enabled() && switch (purpose) {
            case NORMAL, FINALIZATION, RECOVERY -> true;
            default -> false;
        };
        if (allowEdits && view.protectedTokens() < options.triggerTokens()) {
            if (overflow || view.effectiveTokens() >= options.snipTriggerTokens()) {
                var snipper = new HistorySnipper();
                commitDeterministic(view, options.snipTargetTokens(), sink, state -> snipper.propose(state, options));
                view = capture(client, fixedTools, tracker, refresh.get(), true, false, sendView);
            }
            if (overflow || view.effectiveTokens() >= options.microTriggerTokens()) {
                var microcompactor = new ToolResultMicrocompactor();
                commitDeterministic(view, options.microTargetTokens(), sink, state -> microcompactor.propose(state, options));
                view = capture(client, fixedTools, tracker, refresh.get(), true, false, sendView);
            }
            if (overflow || view.effectiveTokens() >= options.collapseTriggerTokens()) {
                boolean staleRetried = false;
                int committed = 0;
                while (committed < options.maxCollapseIntervals()
                        && (committed == 0 || view.effectiveTokens() >= options.summaryTargetTokens())) {
                    var candidate = summaries.collapse(view.source(), view.nodes());
                    if (candidate.isEmpty()) break;
                    var transaction = transaction(view.source(), List.of(candidate.get()));
                    if (!withinPayloadLimit(transaction)) break;
                    var outcome = sink.commit(transaction);
                    view = capture(client, fixedTools, tracker, refresh.get(), true, false, sendView);
                    if (outcome == ContextEditSink.CommitResult.STALE) {
                        if (staleRetried || view.protectedTokens() >= options.triggerTokens()) break;
                        staleRetried = true;
                    } else {
                        committed++;
                    }
                }
            }
        }
        // The Auto host protects its own suffix. Historical user boundaries pin local layers only.
        if (allowEdits && (overflow || view.effectiveTokens() >= options.triggerTokens())
                && view.fixedTokens() < options.triggerTokens()) {
            summaries.auto(view.source(), view.nodes());
            view = capture(client, fixedTools, tracker, refresh.get(), true, false, sendView);
        }
        boolean enforceLimit = options.enabled() || overflow || purpose == RequestPurpose.REVIEW
                || !view.source().activeEdits().isEmpty();
        boolean sendable = !enforceLimit || view.effectiveTokens() <= options.triggerTokens();
        Reason reason = sendable ? Reason.READY : view.fixedTokens() >= options.triggerTokens()
                ? Reason.PROTECTED_CONTENT_EXCEEDS_BUDGET : Reason.NO_ELIGIBLE_REDUCTION;
        long ineligible = sendable ? 0 : Math.max(0, view.prediction().fullEstimateTokens()
                + view.prediction().safetyMarginTokens() - view.protectedTokens());
        return new Result(view.messages(), fixedTools, view.request(), view.prediction(), sendable, reason,
                view.protectedTokens(), ineligible);
    }

    private View capture(LlmClient client, List<Tool> tools, ContextTokenTracker tracker,
                         Snapshot source, boolean publish, boolean overflow,
                         Function<List<ContextNode>, List<ContextNode>> sendView) {
        List<ContextNode> projected = ContextEditReplay.project(scope(source), source.protection());
        List<ContextNode> nodes = List.copyOf(sendView.apply(projected));
        List<Message> messages = nodes.stream().map(ContextNode::message).toList();
        RequestSnapshot request = snapshots.capture(client, messages, tools, source.sourceRevision());
        var visibleEditIds = new HashSet<String>();
        projected.stream().map(ContextNode::id).filter(id -> id.kind() == NodeId.Kind.EDIT)
                .forEach(id -> visibleEditIds.add(id.editId()));
        // REMOVE_SPAN has no replacement node. Its visibility is observable through omitted source identities.
        Set<NodeId> visibleNodes = new HashSet<>(DeterministicContextEdits.ids(projected));
        source.activeEdits().stream().filter(edit -> !visibleNodes.contains(edit.orderedSourceIds().get(0)))
                .forEach(edit -> visibleEditIds.add(edit.editId()));
        String viewId = ContextDigests.canonicalDigest(List.of(source.scopeId(), source.contextGeneration(),
                visibleEditIds.stream().sorted().toList(), source.protection().requestFingerprints()));
        var prediction = tracker.predictProjected(request, viewId, publish, overflow);
        long envelopeTokens = (long) request.estimatedToolTokens() + prediction.safetyMarginTokens();
        long protectedTokens = DeterministicContextEdits.tokens(nodes.stream()
                .filter(n -> n.id().kind() == NodeId.Kind.REQUEST || DeterministicContextEdits.protectedNode(source, n)).toList())
                + envelopeTokens;
        long fixedTokens = DeterministicContextEdits.tokens(nodes.stream()
                .filter(n -> n.id().kind() == NodeId.Kind.REQUEST || source.protection().protectedNodeIds().contains(n.id())).toList()) + envelopeTokens;
        return new View(source, nodes, messages, request, prediction, protectedTokens, fixedTokens);
    }

    private static void commitDeterministic(View view, long target, ContextEditSink sink,
                                            Function<Snapshot, Optional<ContextEdit>> propose) throws IOException {
        Snapshot base = view.source();
        Snapshot simulated = base;
        var edits = new ArrayList<ContextEdit>();
        ContextEditBatch transaction = null;
        String baseDigest = ContextEditReplay.baseDigest(scope(base));
        String protectionDigest = base.protection().digest();
        while (true) {
            var candidate = propose.apply(simulated);
            if (candidate.isEmpty()) break;
            var edit = candidate.get();
            edits.add(edit);
            var next = transaction(base, edits, baseDigest, protectionDigest);
            if (!withinPayloadLimit(next)) break;
            transaction = next;
            var active = new ArrayList<>(simulated.activeEdits());
            active.removeIf(old -> edit.retiredEditIds().contains(old.editId()));
            active.add(edit);
            simulated = new Snapshot(base.scopeId(), base.sourceRevision(), base.contextGeneration(),
                    base.sourceNodes(), active, base.protection());
            var projected = ContextEditReplay.project(scope(simulated), base.protection());
            long localEstimate = DeterministicContextEdits.tokens(projected)
                    + (long) view.request().estimatedToolTokens() + view.prediction().safetyMarginTokens();
            if (localEstimate < target) break;
        }
        if (transaction != null) {
            ContextEditReplay.apply(scope(base), transaction, base.protection());
            sink.commit(transaction);
        }
    }

    private static ContextScopeState scope(Snapshot snapshot) {
        return new ContextScopeState(snapshot.scopeId(), snapshot.sourceRevision(), snapshot.contextGeneration(),
                snapshot.sourceNodes(), snapshot.activeEdits());
    }

    private static ContextEditBatch transaction(Snapshot source, List<ContextEdit> edits) {
        return transaction(source, edits, ContextEditReplay.baseDigest(scope(source)), source.protection().digest());
    }

    private static ContextEditBatch transaction(Snapshot source, List<ContextEdit> edits,
                                                String baseDigest, String protectionDigest) {
        String operationId = ContextDigests.canonicalDigest(List.of(source.scopeId(), source.sourceRevision(),
                source.contextGeneration(), protectionDigest, baseDigest, edits));
        return new ContextEditBatch(operationId, source.scopeId(), source.sourceRevision(),
                source.contextGeneration(), protectionDigest, baseDigest, edits);
    }

    private static boolean withinPayloadLimit(ContextEditBatch transaction) {
        try {
            return DeterministicContextEdits.JSON.writeValueAsBytes(transaction).length <= MAX_BATCH_BYTES;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize context edit", e);
        }
    }
}
