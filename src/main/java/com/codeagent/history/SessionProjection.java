package com.codeagent.history;

import com.codeagent.context.MeasuredUsage;
import com.codeagent.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Immutable state derived from a session event prefix. */
public record SessionProjection(
        List<SurfaceNode> activeSurface,
        List<ConversationNode> topLevelConversation,
        Map<String, OpenTurn> openTurns,
        long lastAppliedSequence,
        long historyVersion,
        long compactionGeneration,
        MeasuredUsageFact lastCompletedUsage,
        Set<String> incompleteRequestIds,
        Map<String, PendingToolInvocation> pendingTools,
        boolean cleanlyClosed,
        List<String> warnings) {

    public SessionProjection {
        activeSurface = List.copyOf(activeSurface);
        topLevelConversation = List.copyOf(topLevelConversation);
        openTurns = Map.copyOf(openTurns);
        incompleteRequestIds = Set.copyOf(incompleteRequestIds);
        pendingTools = Map.copyOf(pendingTools);
        warnings = List.copyOf(warnings);
    }

    public List<LlmClient.Message> messages() {
        return activeSurface.stream().map(SurfaceNode::message).toList();
    }

    public List<LlmClient.Message> conversationMessages() {
        return topLevelConversation.stream().map(ConversationNode::message).toList();
    }

    public Optional<OpenTurn> openPlanTurn(String planId) {
        if (planId == null || planId.isBlank()) {
            return Optional.empty();
        }
        return openTurns.values().stream()
                .filter(turn -> planId.equals(turn.activePlanId()) || turn.planIds().contains(planId))
                .findFirst();
    }

    public SessionProjection withWarning(String warning) {
        List<String> updatedWarnings = new ArrayList<>(warnings);
        updatedWarnings.add(warning);
        return new SessionProjection(activeSurface, topLevelConversation, openTurns,
                lastAppliedSequence, historyVersion, compactionGeneration, lastCompletedUsage,
                incompleteRequestIds, pendingTools, cleanlyClosed, updatedWarnings);
    }

    public record SurfaceNode(long sequence, LlmClient.Message message) {
    }

    public record ConversationNode(long sequence,
                                   String turnId,
                                   String planId,
                                   String mode,
                                   LlmClient.Message message,
                                   ConversationKind kind) {
    }

    public enum ConversationKind {
        USER,
        ASSISTANT,
        SUMMARY
    }

    public record OpenTurn(String turnId,
                           String rootPlanId,
                           String activePlanId,
                           List<String> planIds) {
        public OpenTurn {
            planIds = List.copyOf(planIds);
        }
    }

    public record MeasuredUsageFact(String requestId, String provider, String model,
                                    MeasuredUsage usage) {
    }

    public record PendingToolInvocation(String invocationId, String name, String arguments) {
    }
}
