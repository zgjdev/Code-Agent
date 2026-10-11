package com.codeagent.runtime.interaction;

import com.codeagent.runtime.execution.WorkspaceKey;
import java.util.ArrayDeque;
import java.util.Optional;

/** A surface answers only the explicitly displayed identity, regardless of UI Session selection. */
public final class InteractionPresentationQueue {
    private final ArrayDeque<Entry> pending = new ArrayDeque<>();
    public synchronized void submit(WorkspaceKey workspace, InteractionRequest request, InteractionBroker broker) {
        if (broker.pending().filter(value -> value.equals(request)).isEmpty()) {
            throw new IllegalStateException("Interaction is no longer pending");
        }
        if (pending.stream().anyMatch(e -> e.workspace().equals(workspace)
                && e.request().interactionId().equals(request.interactionId()))) return;
        pending.addLast(new Entry(workspace, request, broker));
    }
    public synchronized Optional<Entry> focused() {
        pending.removeIf(e -> e.broker().pending().filter(r -> r.equals(e.request())).isEmpty());
        return Optional.ofNullable(pending.peekFirst());
    }
    public synchronized boolean respond(WorkspaceKey workspace, String executionId, String interactionId,
                                         String action, String text) {
        Entry current = focused().orElse(null);
        if (current == null || !current.workspace().equals(workspace)
                || !current.request().executionId().equals(executionId)
                || !current.request().interactionId().equals(interactionId)) return false;
        boolean accepted = current.broker().respond(executionId, interactionId, action, text);
        if (accepted) pending.removeFirst();
        return accepted;
    }
    public synchronized void cancelExecution(WorkspaceKey workspace, String executionId, String reason) {
        pending.removeIf(e -> {
            if (!e.workspace().equals(workspace) || !e.request().executionId().equals(executionId)) return false;
            e.broker().cancelExecution(executionId, reason);
            return true;
        });
    }
    public record Entry(WorkspaceKey workspace, InteractionRequest request, InteractionBroker broker) { }
}
