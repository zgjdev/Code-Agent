package com.codeagent.agent;

import com.codeagent.history.SessionProjection;
import com.codeagent.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionModeRoutingContextTest {

    private final ExecutionModeRoutingContext context = new ExecutionModeRoutingContext();

    @Test
    void keepsOnlyTheMostRecentThreeUserTurns() {
        List<SessionProjection.ConversationNode> selected = context.select(List.of(
                user(1, "u1"), assistant(2, "a1"),
                user(3, "u2"), assistant(4, "a2"),
                user(5, "u3"), assistant(6, "a3"),
                user(7, "u4"), assistant(8, "a4")
        ));

        assertEquals(List.of("u2", "a2", "u3", "a3", "u4", "a4"), contents(selected));
    }

    @Test
    void prependsLatestSummaryWhenOlderTurnsAreTrimmed() {
        List<SessionProjection.ConversationNode> selected = context.select(List.of(
                user(1, "discarded"),
                summary(2, "summary"),
                user(3, "u1"), assistant(4, "a1"),
                user(5, "u2"), assistant(6, "a2"),
                user(7, "u3"), assistant(8, "a3"),
                user(9, "u4")
        ));

        assertEquals(List.of("summary", "u2", "a2", "u3", "a3", "u4"), contents(selected));
    }

    @Test
    void latestSummaryIsSemanticLowerBoundForShortHistory() {
        List<SessionProjection.ConversationNode> selected = context.select(List.of(
                user(1, "discarded"), assistant(2, "discarded-answer"),
                summary(3, "summary"), assistant(4, "unclosed-assistant"), user(5, "current-history")
        ));

        assertEquals(List.of("summary", "unclosed-assistant", "current-history"), contents(selected));
    }

    @Test
    void emptyAndNullHistoriesProduceEmptyWindow() {
        assertEquals(List.of(), context.select(null));
        assertEquals(List.of(), context.select(List.of()));
    }

    private static List<String> contents(List<SessionProjection.ConversationNode> nodes) {
        return nodes.stream().map(node -> node.message().content()).toList();
    }

    private static SessionProjection.ConversationNode user(long sequence, String content) {
        return node(sequence, content, SessionProjection.ConversationKind.USER);
    }

    private static SessionProjection.ConversationNode assistant(long sequence, String content) {
        return node(sequence, content, SessionProjection.ConversationKind.ASSISTANT);
    }

    private static SessionProjection.ConversationNode summary(long sequence, String content) {
        return node(sequence, content, SessionProjection.ConversationKind.SUMMARY);
    }

    private static SessionProjection.ConversationNode node(long sequence, String content,
                                                           SessionProjection.ConversationKind kind) {
        String role = kind == SessionProjection.ConversationKind.ASSISTANT ? "assistant" : "user";
        return new SessionProjection.ConversationNode(
                sequence, "turn-" + sequence, null, "react",
                new LlmClient.Message(role, content), kind);
    }
}
