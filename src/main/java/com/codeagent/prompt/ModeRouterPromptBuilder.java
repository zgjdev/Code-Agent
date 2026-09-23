package com.codeagent.prompt;

import com.codeagent.agent.ExecutionModeRoutingContext;
import com.codeagent.history.SessionProjection;
import com.codeagent.history.TopLevelConversationFormatter;
import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Objects;

/** Builds the isolated two-message request used only for execution-mode classification. */
public final class ModeRouterPromptBuilder {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PromptRepository promptRepository;
    private final ExecutionModeRoutingContext routingContext;
    private final TopLevelConversationFormatter formatter;

    public ModeRouterPromptBuilder(PromptRepository promptRepository) {
        this(promptRepository, new ExecutionModeRoutingContext(), new TopLevelConversationFormatter());
    }

    ModeRouterPromptBuilder(PromptRepository promptRepository,
                            ExecutionModeRoutingContext routingContext,
                            TopLevelConversationFormatter formatter) {
        this.promptRepository = Objects.requireNonNull(promptRepository, "promptRepository");
        this.routingContext = Objects.requireNonNull(routingContext, "routingContext");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
    }

    public List<LlmClient.Message> build(List<SessionProjection.ConversationNode> history,
                                         String submittedInput) {
        String systemPrompt = promptRepository.loadRequired("modes/router.md");
        String conversationContext = formatter.format(routingContext.select(history));
        ObjectNode payload = JSON.createObjectNode();
        payload.put("conversationContext", conversationContext);
        payload.put("submittedInput", submittedInput == null ? "" : submittedInput);
        try {
            return List.of(
                    LlmClient.Message.system(systemPrompt),
                    LlmClient.Message.user(JSON.writeValueAsString(payload))
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize mode router input", e);
        }
    }
}
