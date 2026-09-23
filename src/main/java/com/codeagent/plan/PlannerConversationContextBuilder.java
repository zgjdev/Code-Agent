package com.codeagent.plan;

import com.codeagent.history.SessionProjection;

import java.util.List;

/** Deterministically serializes top-level session conversation for Planner input. */
public final class PlannerConversationContextBuilder {

    public String build(List<SessionProjection.ConversationNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("[历史会话上下文]\n");
        for (SessionProjection.ConversationNode node : nodes) {
            if (node == null || node.message() == null) {
                continue;
            }
            String content = node.message().content();
            if (content == null || content.isBlank()) {
                continue;
            }
            String label = switch (node.kind()) {
                case USER -> "User";
                case ASSISTANT -> "Assistant";
                case SUMMARY -> "Summary";
            };
            out.append('[').append(label).append("] ")
                    .append(content.trim()).append('\n');
        }
        return out.toString().trim();
    }
}
