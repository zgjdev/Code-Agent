package com.codeagent.agent;

import com.codeagent.context.ContextDigests;
import com.codeagent.context.ContextNode;
import com.codeagent.context.NodeId;
import com.codeagent.context.SourceKind;
import com.codeagent.history.*;
import com.codeagent.llm.LlmClient;
import com.codeagent.model.recovery.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Sequence-based send-copy composition. Never appends to the Session or compaction candidate. */
public final class RecoveryRequestAugmenter {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String CONTROL_ACTOR = "continuation-control";
    public static final String CONTROL_SOURCE = "continuation-control";
    private final String inputPrefix;
    private final String inputSuffix;

    public RecoveryRequestAugmenter() { this("", ""); }

    RecoveryRequestAugmenter(String inputPrefix, String inputSuffix) {
        this.inputPrefix = Objects.requireNonNull(inputPrefix);
        this.inputSuffix = Objects.requireNonNull(inputSuffix);
    }

    public List<LlmClient.Message> augmentRequest(List<LlmClient.Message> baseMessages,
            FormattedRecoveryContext context, RequestAssemblyBoundary boundary, SessionStore.SessionHandle session) {
        return compose(baseMessages, Objects.requireNonNull(context), boundary, session);
    }

    /** The exact send view before inserting the recovery block, including any restored input parts. */
    List<LlmClient.Message> requestBaseMessages(List<LlmClient.Message> baseMessages,
            RequestAssemblyBoundary boundary, SessionStore.SessionHandle session) {
        return compose(baseMessages, null, boundary, session);
    }

    private List<LlmClient.Message> compose(List<LlmClient.Message> baseMessages,
            FormattedRecoveryContext context, RequestAssemblyBoundary boundary, SessionStore.SessionHandle session) {
        var original = inspect(baseMessages, boundary, session);
        var nodes = original.projection().activeSurface();
        var user = original.user();
        var result = original.result();
        var indexed = original.events();
        var executionSuffix = original.suffix();
        return composeMessages(baseMessages, context, boundary, nodes, user, result, indexed, executionSuffix);
    }

    private static Original inspect(List<LlmClient.Message> baseMessages, RequestAssemblyBoundary boundary,
                                    SessionStore.SessionHandle session) {
        Objects.requireNonNull(boundary); Objects.requireNonNull(session);
        var projection = session.projection();
        var nodes = projection.activeSurface();
        if (nodes.size() != baseMessages.size()) throw new IllegalArgumentException("Recovery surface out of sync");
        var input = projection.executionEnvelopes().get(boundary.inputExecutionId());
        var result = projection.executionEnvelopes().get(boundary.resultExecutionId());
        if (input == null || result == null || result.ended() || result.assistantSequence() != null
                || !Objects.equals(input.userSequence(), boundary.inputUserSequence()))
            throw new IllegalArgumentException("Recovery execution boundary unavailable");
        var events = session.readAll();
        SessionEvent user = events.stream().filter(e -> e.sequence() == boundary.inputUserSequence()).findFirst().orElseThrow(
                () -> new IllegalArgumentException("Recovery user source missing"));
        if (!session.sessionId().equals(user.sessionId()) || !SessionEvent.Types.USER_MESSAGE.equals(user.type())
                || !sourceAllowed(user) || !boundary.inputExecutionId().equals(user.payload().path("conversation").path("executionId").asText())
                || !"user".equals(user.payload().path("conversation").path("role").asText()))
            throw new IllegalArgumentException("Recovery user identity mismatch");
        Map<Long, SessionEvent> indexed = new HashMap<>();
        events.forEach(e -> indexed.put(e.sequence(), e));
        Set<Long> executionSuffix = executionSuffix(events, boundary);
        return new Original(projection, user, result, indexed, executionSuffix);
    }

    private record Original(SessionProjection projection, SessionEvent user, SessionProjection.ExecutionEnvelope result,
                            Map<Long, SessionEvent> events, Set<Long> suffix) {}

    private List<LlmClient.Message> composeMessages(List<LlmClient.Message> baseMessages,
            FormattedRecoveryContext context, RequestAssemblyBoundary boundary, List<SessionProjection.SurfaceNode> nodes,
            SessionEvent user, SessionProjection.ExecutionEnvelope result, Map<Long, SessionEvent> indexed,
            Set<Long> executionSuffix) {
        List<LlmClient.Message> copy = new ArrayList<>();
        boolean inserted = false;
        boolean userOnSurface = nodes.stream().anyMatch(n -> n.sequence() == boundary.inputUserSequence());
        // A never-started queued E1 can persist its first user only after E2 has ended. It is
        // background, so move just that explicitly-owned node before E2 in the send copy.
        Long lateGoalSequence = result.userSequence() != null && result.userSequence() > boundary.inputUserSequence()
                && !boundary.resultExecutionId().equals(boundary.inputExecutionId()) ? result.userSequence() : null;
        LlmClient.Message lateGoal = null;
        if (lateGoalSequence != null) {
            for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).sequence() == lateGoalSequence) lateGoal = baseMessages.get(i);
        }
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            var event = indexed.get(node.sequence());
            if (controlReceipt(event)) continue;
            if (lateGoal != null && Objects.equals(lateGoalSequence, node.sequence())) continue;
            if (node.sequence() == boundary.inputUserSequence()) {
                if (lateGoal != null) copy.add(lateGoal);
                if (context != null) copy.add(LlmClient.Message.user(context.content()));
                inserted = true;
                copy.add(enrichInput(baseMessages.get(i)));
                continue;
            } else if (!inserted && !userOnSurface && executionSuffix.contains(node.sequence())) {
                if (lateGoal != null) copy.add(lateGoal);
                if (context != null) copy.add(LlmClient.Message.user(context.content()));
                copy.add(enrichInput(rematerialize(user)));
                inserted = true;
            }
            copy.add(baseMessages.get(i));
        }
        if (!inserted) {
            if (lateGoal != null) copy.add(lateGoal);
            if (context != null) copy.add(LlmClient.Message.user(context.content()));
            copy.add(enrichInput(rematerialize(user)));
        }
        return List.copyOf(copy);
    }

    /** Validates the original surface and captures a recipe safe to apply to a shorter projection. */
    RecoveryView composeNodes(List<ContextNode> source, FormattedRecoveryContext context,
                              RequestAssemblyBoundary boundary, SessionStore.SessionHandle session) {
        var original = inspect(source.stream().map(ContextNode::message).toList(), boundary, session);
        var surface = original.projection().activeSurface();
        for (int i = 0; i < source.size(); i++) {
            if (!source.get(i).id().equals(NodeId.raw(session.sessionId(), surface.get(i).sequence()))
                    || !source.get(i).message().equals(surface.get(i).message())) {
                throw new IllegalArgumentException("Recovery surface source identity mismatch");
            }
        }
        Long late = original.result().userSequence();
        if (late != null && (late <= boundary.inputUserSequence()
                || boundary.resultExecutionId().equals(boundary.inputExecutionId()))) late = null;
        Set<NodeId> pinned = new HashSet<>();
        Set<NodeId> receipts = new HashSet<>();
        ContextNode input = null;
        ContextNode lateGoal = null;
        for (var node : source) {
            long seq = node.id().sourceSequence();
            if (controlReceipt(original.events().get(seq))) receipts.add(node.id());
            if (original.suffix().contains(seq) || receipts.contains(node.id())) pinned.add(node.id());
            if (seq == boundary.inputUserSequence()) input = node;
            if (Objects.equals(late, seq)) lateGoal = node;
        }
        if (input != null) pinned.add(input.id());
        if (lateGoal != null) pinned.add(lateGoal.id());
        String assembly = ContextDigests.canonicalDigest(boundary);
        ContextNode inputNode = new ContextNode(NodeId.request(assembly, 1),
                enrichInput(input == null ? rematerialize(original.user()) : input.message()),
                SourceKind.RECOVERY_CONTROL, boundary.inputExecutionId(), null, null, null);
        ContextNode block = context == null ? null : new ContextNode(NodeId.request(assembly, 0),
                LlmClient.Message.user(context.content()), SourceKind.RECOVERY_CONTROL,
                boundary.resultExecutionId(), null, null, null);
        return new RecoveryView(Set.copyOf(pinned), Set.copyOf(receipts), original.suffix(),
                input == null ? null : input.id(), lateGoal, inputNode, block);
    }

    record RecoveryView(Set<NodeId> pinned, Set<NodeId> receipts, Set<Long> suffix, NodeId inputId,
                        ContextNode lateGoal, ContextNode input, ContextNode block) {
        List<ContextNode> apply(List<ContextNode> projected) {
            var result = new ArrayList<ContextNode>();
            boolean inserted = false;
            for (var node : projected) {
                if (receipts.contains(node.id()) || lateGoal != null && lateGoal.id().equals(node.id())) continue;
                boolean atInput = node.id().equals(inputId);
                if (!inserted && (atInput || inputId == null && node.id().sourceSequence() != null
                        && suffix.contains(node.id().sourceSequence()))) {
                    insert(result);
                    inserted = true;
                }
                if (!atInput) result.add(node);
            }
            if (!inserted) insert(result);
            return List.copyOf(result);
        }

        String fingerprint() {
            return ContextDigests.canonicalDigest(apply(List.of()));
        }

        private void insert(List<ContextNode> result) {
            if (lateGoal != null) result.add(lateGoal);
            if (block != null) result.add(block);
            result.add(input);
        }
    }

    private LlmClient.Message enrichInput(LlmClient.Message input) {
        if (inputPrefix.isEmpty() && inputSuffix.isEmpty()) return input;
        if (!input.hasContentParts())
            return LlmClient.Message.user(inputPrefix + Objects.toString(input.content(), "") + inputSuffix);
        var parts = new ArrayList<LlmClient.ContentPart>();
        if (!inputPrefix.isEmpty()) parts.add(LlmClient.ContentPart.text(inputPrefix));
        parts.addAll(input.contentParts());
        if (!inputSuffix.isEmpty()) parts.add(LlmClient.ContentPart.text(inputSuffix));
        return LlmClient.Message.user(parts);
    }

    /** Raw sequence establishes causality, while activeSurface establishes projected order.
     * Compaction messages have fresh sequences but replace historical positions. They never
     * establish the new execution suffix, even when a request preceded their durable commit. */
    private static Set<Long> executionSuffix(List<SessionEvent> events, RequestAssemblyBoundary boundary) {
        Set<Long> suffix = new HashSet<>();
        Set<String> ownedRequests = new HashSet<>(), ownedInvocations = new HashSet<>();
        boolean currentRequestOwned = false;
        for (var event : events) {
            if (event.sequence() <= boundary.inputUserSequence()) continue;
            var payload = event.payload();
            if (SessionEvent.Types.REQUEST_STARTED.equals(event.type())) {
                currentRequestOwned = agentSource(event)
                        && boundary.resultExecutionId().equals(payload.path("executionId").asText())
                        && !payload.path("requestId").asText().isBlank();
                if (currentRequestOwned) ownedRequests.add(payload.path("requestId").asText());
            }
            if (!agentSource(event) || payload.hasNonNull("compactionId")
                    || !"append".equals(event.surface().op())) continue;
            if (SessionEvent.Types.ASSISTANT_MESSAGE.equals(event.type())
                    && ownedRequests.contains(payload.path("requestId").asText())) {
                suffix.add(event.sequence());
                for (var call : payload.path("message").path("toolCalls"))
                    if (!call.path("id").asText().isBlank()) ownedInvocations.add(call.path("id").asText());
            } else if (SessionEvent.Types.TOOL_RESULT.equals(event.type())
                    && ownedInvocations.contains(payload.path("invocationId").asText())) {
                suffix.add(event.sequence());
            } else if (SessionEvent.Types.USER_MESSAGE.equals(event.type()) && currentRequestOwned
                    && !payload.hasNonNull("conversation")) {
                // LSP/image/wrapup messages have no top-level user identity. Their enclosing
                // request's explicit owner distinguishes them from later conversational turns.
                suffix.add(event.sequence());
            }
        }
        return suffix;
    }

    private static boolean agentSource(SessionEvent event) {
        return "react".equals(event.mode()) && "agent".equals(event.actor()) && "agent".equals(event.source());
    }

    private static LlmClient.Message rematerialize(SessionEvent event) {
        try {
            var message = JSON.treeToValue(event.payload().path("message"), LlmClient.Message.class);
            if (message == null || !"user".equals(message.role()))
                throw new IllegalArgumentException("Recovery user message unavailable");
            return message;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("Recovery user message unavailable");
        }
    }
    private static boolean sourceAllowed(SessionEvent e) {
        return ("react".equals(e.mode()) && (("agent".equals(e.actor()) && "agent".equals(e.source()))
                || (CONTROL_ACTOR.equals(e.actor()) && CONTROL_SOURCE.equals(e.source()))))
                || ("plan".equals(e.mode()) && "plan-agent".equals(e.actor()) && "plan-conversation".equals(e.source()));
    }
    private static boolean controlReceipt(SessionEvent e) {
        return e != null && "react".equals(e.mode()) && CONTROL_ACTOR.equals(e.actor()) && CONTROL_SOURCE.equals(e.source())
                && SessionEvent.Types.ASSISTANT_MESSAGE.equals(e.type()) && "assistant".equals(e.payload().path("conversation").path("role").asText());
    }
}
