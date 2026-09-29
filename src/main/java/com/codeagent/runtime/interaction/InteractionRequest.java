package com.codeagent.runtime.interaction;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public record InteractionRequest(
        String interactionId,
        String executionId,
        String sessionId,
        InteractionKind kind,
        Set<String> allowedActions,
        String prompt) {

    public InteractionRequest {
        requireText(interactionId, "interactionId");
        requireText(executionId, "executionId");
        requireText(sessionId, "sessionId");
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (allowedActions == null || allowedActions.isEmpty()) {
            throw new IllegalArgumentException("allowedActions must not be empty");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String action : allowedActions) {
            requireText(action, "allowedAction");
            normalized.add(action.trim().toLowerCase(Locale.ROOT));
        }
        allowedActions = Set.copyOf(normalized);
        prompt = prompt == null ? "" : prompt;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
