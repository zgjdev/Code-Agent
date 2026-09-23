package com.codeagent.agent;

import com.codeagent.history.SessionProjection;

import java.util.ArrayList;
import java.util.List;

/** Selects the bounded top-level semantic history visible to the mode router. */
public final class ExecutionModeRoutingContext {
    private static final int MAX_USER_TURNS = 3;

    public List<SessionProjection.ConversationNode> select(
            List<SessionProjection.ConversationNode> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        List<SessionProjection.ConversationNode> valid = history.stream()
                .filter(node -> node != null && node.message() != null && node.kind() != null)
                .toList();
        if (valid.isEmpty()) {
            return List.of();
        }

        int summaryIndex = -1;
        for (int i = 0; i < valid.size(); i++) {
            if (valid.get(i).kind() == SessionProjection.ConversationKind.SUMMARY) {
                summaryIndex = i;
            }
        }
        List<SessionProjection.ConversationNode> bounded = valid.subList(
                summaryIndex < 0 ? 0 : summaryIndex, valid.size());

        int userCount = 0;
        for (SessionProjection.ConversationNode node : bounded) {
            if (node.kind() == SessionProjection.ConversationKind.USER) {
                userCount++;
            }
        }
        if (userCount <= MAX_USER_TURNS) {
            return List.copyOf(bounded);
        }

        int usersToSkip = userCount - MAX_USER_TURNS;
        int firstRetainedUser = 0;
        for (int i = 0; i < bounded.size(); i++) {
            if (bounded.get(i).kind() != SessionProjection.ConversationKind.USER) {
                continue;
            }
            if (usersToSkip == 0) {
                firstRetainedUser = i;
                break;
            }
            usersToSkip--;
        }

        List<SessionProjection.ConversationNode> selected = new ArrayList<>();
        if (bounded.get(0).kind() == SessionProjection.ConversationKind.SUMMARY) {
            selected.add(bounded.get(0));
        }
        selected.addAll(bounded.subList(firstRetainedUser, bounded.size()));
        return List.copyOf(selected);
    }
}
