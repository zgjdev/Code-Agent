package com.codeagent.agent;

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
            } else if (!inserted && !userOnSurface && executionSuffix.contains(node.sequence())) {
                if (lateGoal != null) copy.add(lateGoal);
                if (context != null) copy.add(LlmClient.Message.user(context.content()));
                copy.add(rematerialize(user));
                inserted = true;
            }
            copy.add(baseMessages.get(i));
        }
        if (!inserted) {
            if (lateGoal != null) copy.add(lateGoal);
            if (context != null) copy.add(LlmClient.Message.user(context.content()));
            copy.add(rematerialize(user));
        }
        return List.copyOf(copy);
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
