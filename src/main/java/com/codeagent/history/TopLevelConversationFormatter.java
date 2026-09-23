package com.codeagent.history;

import java.util.List;

/** Deterministically serializes top-level semantic conversation nodes. */
public final class TopLevelConversationFormatter {

    public String format(List<SessionProjection.ConversationNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("[历史会话上下文]\n");
        for (SessionProjection.ConversationNode node : nodes) {
            if (node == null || node.message() == null || node.kind() == null) {
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
        String formatted = out.toString().trim();
        return "[历史会话上下文]".equals(formatted) ? "" : formatted;
    }
}
