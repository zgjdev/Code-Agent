package com.codeagent.cli;

import com.codeagent.runtime.interaction.InteractionBroker;
import com.codeagent.runtime.interaction.InteractionKind;
import com.codeagent.runtime.interaction.InteractionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

final class InteractionInputRouter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    enum Result {
        NO_PENDING,
        CONSUMED,
        INVALID
    }

    private InteractionInputRouter() {
    }

    static Result route(InteractionBroker broker, String input) {
        InteractionRequest request = broker.pending().orElse(null);
        if (request == null) {
            return Result.NO_PENDING;
        }
        return request.kind() == InteractionKind.PLAN_REVIEW
                ? routePlan(broker, request, input)
                : routeHitl(broker, request, input);
    }

    private static Result routePlan(
            InteractionBroker broker, InteractionRequest request, String input) {
        PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse(input);
        String action = switch (decision.type()) {
            case EXECUTE -> "execute";
            case SUPPLEMENT -> "supplement";
            case CANCEL -> "cancel";
        };
        return respond(broker, request, action, decision.feedback());
    }

    private static Result routeHitl(
            InteractionBroker broker, InteractionRequest request, String input) {
        String trimmed = input == null ? "" : input.trim();
        String[] parts = trimmed.split("\\s+", 2);
        String command = parts.length == 0 ? "" : parts[0].toLowerCase(Locale.ROOT);
        String text = parts.length > 1 ? parts[1].trim() : "";
        String action;
        if (command.isEmpty() || command.equals("y") || command.equals("yes")) {
            action = "approve";
        } else if (command.equals("a") || command.equals("all")) {
            action = "approve_all";
        } else if (command.equals("server")) {
            action = "approve_server";
        } else if (command.equals("n") || command.equals("no") || command.equals("reject")) {
            action = "reject";
        } else if (command.equals("s") || command.equals("skip")) {
            action = "skip";
        } else if (command.equals("m") || command.equals("modify")) {
            if (!isJson(text)) {
                return Result.INVALID;
            }
            action = "modify";
        } else {
            return Result.INVALID;
        }
        return respond(broker, request, action, text);
    }

    private static boolean isJson(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            return MAPPER.readTree(text) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static Result respond(
            InteractionBroker broker, InteractionRequest request, String action, String text) {
        try {
            return broker.respond(request.interactionId(), action, text)
                    ? Result.CONSUMED
                    : Result.INVALID;
        } catch (IllegalArgumentException e) {
            return Result.INVALID;
        }
    }
}
