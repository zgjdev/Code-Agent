package com.codeagent.runtime.interaction;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class InteractionBroker implements AutoCloseable {
    private Pending pending;
    private boolean closed;

    public synchronized CompletableFuture<InteractionResponse> request(InteractionRequest request) {
        if (closed) {
            throw new IllegalStateException("Interaction broker is closed");
        }
        if (pending != null) {
            throw new IllegalStateException(
                    "Another interaction is already pending: " + pending.request.interactionId());
        }
        CompletableFuture<InteractionResponse> response = new CompletableFuture<>();
        pending = new Pending(request, response);
        return response;
    }

    public synchronized Optional<InteractionRequest> pending() {
        return pending == null ? Optional.empty() : Optional.of(pending.request);
    }

    public synchronized boolean respond(String interactionId, String action, String text) {
        if (pending == null || interactionId == null
                || !pending.request.interactionId().equals(interactionId)) {
            return false;
        }
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!pending.request.allowedActions().contains(normalized)) {
            throw new IllegalArgumentException(
                    "Action is not allowed for interaction " + interactionId + ": " + action);
        }
        Pending completed = pending;
        pending = null;
        completed.response.complete(new InteractionResponse(
                interactionId, normalized, text == null ? "" : text));
        return true;
    }

    public synchronized boolean cancelExecution(String executionId, String reason) {
        if (pending == null || executionId == null
                || !pending.request.executionId().equals(executionId)) {
            return false;
        }
        Pending canceled = pending;
        pending = null;
        canceled.response.completeExceptionally(new IllegalStateException(
                reason == null || reason.isBlank() ? "Execution ended" : reason));
        return true;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (pending != null) {
            Pending canceled = pending;
            pending = null;
            canceled.response.completeExceptionally(
                    new IllegalStateException("Runtime interaction broker shut down"));
        }
    }

    private record Pending(
            InteractionRequest request, CompletableFuture<InteractionResponse> response) {
    }
}
