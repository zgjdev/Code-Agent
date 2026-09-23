package com.codeagent.history;

import com.codeagent.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopLevelConversationFormatterTest {

    @Test
    void formatsConversationNodesWithStableLabels() {
        TopLevelConversationFormatter formatter = new TopLevelConversationFormatter();

        String formatted = formatter.format(List.of(
                node(1, SessionProjection.ConversationKind.SUMMARY, " earlier context "),
                node(2, SessionProjection.ConversationKind.USER, "question"),
                node(3, SessionProjection.ConversationKind.ASSISTANT, "answer")
        ));

        assertEquals("""
                [历史会话上下文]
                [Summary] earlier context
                [User] question
                [Assistant] answer""", formatted);
    }

    @Test
    void ignoresNullNodesAndBlankMessages() {
        TopLevelConversationFormatter formatter = new TopLevelConversationFormatter();

        String formatted = formatter.format(java.util.Arrays.asList(
                null,
                node(1, SessionProjection.ConversationKind.USER, "  "),
                node(2, SessionProjection.ConversationKind.USER, "kept")
        ));

        assertEquals("[历史会话上下文]\n[User] kept", formatted);
        assertEquals("", formatter.format(null));
        assertEquals("", formatter.format(List.of()));
    }

    private static SessionProjection.ConversationNode node(
            long sequence, SessionProjection.ConversationKind kind, String content) {
        String role = kind == SessionProjection.ConversationKind.ASSISTANT ? "assistant" : "user";
        return new SessionProjection.ConversationNode(
                sequence, "turn-" + sequence, null, "react",
                new LlmClient.Message(role, content), kind);
    }
}
