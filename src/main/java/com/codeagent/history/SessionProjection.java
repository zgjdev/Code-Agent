package com.codeagent.history;

import com.codeagent.context.MeasuredUsage;
import com.codeagent.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable state derived from a session event prefix. */
public record SessionProjection(
        List<SurfaceNode> activeSurface,
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
        incompleteRequestIds = Set.copyOf(incompleteRequestIds);
        pendingTools = Map.copyOf(pendingTools);
        warnings = List.copyOf(warnings);
    }

    public List<LlmClient.Message> messages() {
        return activeSurface.stream().map(SurfaceNode::message).toList();
    }

    public SessionProjection withWarning(String warning) {
        List<String> updatedWarnings = new ArrayList<>(warnings);
        updatedWarnings.add(warning);
        return new SessionProjection(activeSurface, lastAppliedSequence, historyVersion,
                compactionGeneration, lastCompletedUsage, incompleteRequestIds, pendingTools,
                cleanlyClosed, updatedWarnings);
    }

    public record SurfaceNode(long sequence, LlmClient.Message message) {
    }

    public record MeasuredUsageFact(String requestId, String provider, String model,
                                    MeasuredUsage usage) {
    }

    public record PendingToolInvocation(String invocationId, String name, String arguments) {
    }
}
