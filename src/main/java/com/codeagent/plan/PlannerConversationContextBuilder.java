package com.codeagent.plan;

import com.codeagent.history.SessionProjection;
import com.codeagent.history.TopLevelConversationFormatter;

import java.util.List;

/** Deterministically serializes top-level session conversation for Planner input. */
public final class PlannerConversationContextBuilder {
    private final TopLevelConversationFormatter formatter = new TopLevelConversationFormatter();

    public String build(List<SessionProjection.ConversationNode> nodes) {
        return formatter.format(nodes);
    }
}
