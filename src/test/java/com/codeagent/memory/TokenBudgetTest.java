package com.codeagent.memory;

import com.codeagent.llm.LlmClient;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenBudgetTest {

    @Test
    void shouldCalculateAvailableTokens() {
        TokenBudget budget = new TokenBudget(128000);
        int available = budget.getAvailableForConversation();
        // 128000 - 500(system) - 800(tools) - 2000(response)
        assertEquals(124700, available);
    }

    @Test
    void shouldTrackUsageStats() {
        TokenBudget budget = new TokenBudget(128000);
        budget.recordUsage(1000, 500);
        budget.recordUsage(1200, 600);

        assertEquals(2200, budget.getTotalInputTokens());
        assertEquals(1100, budget.getTotalOutputTokens());
        assertEquals(2, budget.getLlmCallCount());
    }

    @Test
    void shouldEstimateMessageTokens() {
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.system("系统提示"),
                LlmClient.Message.user("用户消息")
        );

        int tokens = TokenBudget.estimateMessagesTokens(messages);
        assertTrue(tokens > 0);
        // 至少包含两条消息的开销
        assertTrue(tokens >= 8); // 2 messages * 4 overhead
    }

    @Test
    void shouldGenerateUsageReport() {
        TokenBudget budget = new TokenBudget(128000);
        budget.recordUsage(1000, 500);

        String report = budget.getUsageReport();
        assertTrue(report.contains("1 次"));
        assertTrue(report.contains("1000"));
    }

    @Test
    void shouldEstimateCompleteRequestIncludingToolSchema() throws Exception {
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.system("系统提示"),
                LlmClient.Message.user("读取文件")
        );
        LlmClient.Tool tool = new LlmClient.Tool(
                "read_file", "读取文件内容",
                new ObjectMapper().readTree("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"));

        int messageTokens = TokenBudget.estimateMessagesTokens(messages);
        int toolTokens = TokenBudget.estimateToolsTokens(List.of(tool));

        assertEquals(messageTokens + toolTokens,
                TokenBudget.estimateRequestTokens(messages, List.of(tool)));
        assertTrue(toolTokens > 0);
    }

    @Test
    void shouldCountToolCallArgumentsInMessageEstimate() {
        LlmClient.Message message = LlmClient.Message.assistant(
                "准备读取文件",
                List.of(new LlmClient.ToolCall(
                        "call-1",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"LoginService.java\"}"))));

        assertTrue(TokenBudget.estimateMessageTokens(message)
                > TokenBudget.estimateMessagesTokens(List.of(LlmClient.Message.assistant("准备读取文件"))));
    }
}
