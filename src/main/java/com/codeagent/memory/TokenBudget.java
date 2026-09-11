package com.codeagent.memory;

import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Token 预算管理器 - 确保对话不会超出模型的上下文窗口
 *
 * 策略：
 * 1. 设定总 token 预算（系统提示 + 工具定义 + 对话历史 + 回复预留）
 * 2. 每次调用 LLM 前检查预算
 * 3. 超出预算时触发压缩或裁剪
 */
public class TokenBudget {
    private static final int MESSAGE_OVERHEAD = 4;
    private static final int CONTENT_PART_OVERHEAD = 1;
    private static final int TOOL_CALL_OVERHEAD = 2;
    private static final int TOOLS_OVERHEAD = 4;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final int contextWindow;    // 模型上下文窗口大小
    private final int reservedForSystem; // 系统提示预留
    private final int reservedForTools;  // 工具定义预留
    private final int reservedForResponse; // 回复预留

    // 累计 token 消耗统计
    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalCachedInputTokens;
    private int llmCallCount;

    public TokenBudget(int contextWindow) {
        this(contextWindow, 500, 800, 2000);
    }

    /**
     * @param contextWindow       模型上下文窗口（如 128K = 131072）
     * @param reservedForSystem   系统提示预留 token 数
     * @param reservedForTools    工具定义预留 token 数
     * @param reservedForResponse 回复预留 token 数
     */
    public TokenBudget(int contextWindow, int reservedForSystem, int reservedForTools, int reservedForResponse) {
        this.contextWindow = contextWindow;
        this.reservedForSystem = reservedForSystem;
        this.reservedForTools = reservedForTools;
        this.reservedForResponse = reservedForResponse;
        this.totalInputTokens = 0;
        this.totalOutputTokens = 0;
        this.totalCachedInputTokens = 0;
        this.llmCallCount = 0;
    }

    /**
     * 获取对话历史可用的 token 预算
     */
    public int getAvailableForConversation() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    /**
     * 检查给定的消息列表是否在预算内
     */
    public boolean isWithinBudget(List<LlmClient.Message> messages) {
        int estimatedTokens = estimateMessagesTokens(messages);
        return estimatedTokens <= getAvailableForConversation();
    }

    /**
     * 记录一次 LLM 调用的 token 消耗
     */
    public void recordUsage(int inputTokens, int outputTokens) {
        recordUsage(inputTokens, outputTokens, 0);
    }

    public void recordUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        totalInputTokens += inputTokens;
        totalOutputTokens += outputTokens;
        totalCachedInputTokens += Math.max(0, cachedInputTokens);
        llmCallCount++;
    }

    /**
     * 获取 token 使用统计
     */
    public String getUsageReport() {
        double avgInput = llmCallCount > 0 ? (double) totalInputTokens / llmCallCount : 0;
        return String.format(
                "Token 统计: 调用 %d 次 | 总输入: %d | 总输出: %d | cached: %d | 平均输入: %.0f | 预算: %d (可用: %d)",
                llmCallCount, totalInputTokens, totalOutputTokens, totalCachedInputTokens, avgInput,
                contextWindow, getAvailableForConversation()
        );
    }

    public int getContextWindow() { return contextWindow; }
    public int getTotalInputTokens() { return totalInputTokens; }
    public int getTotalOutputTokens() { return totalOutputTokens; }
    public int getTotalCachedInputTokens() { return totalCachedInputTokens; }
    public int getLlmCallCount() { return llmCallCount; }

    /**
     * 估算消息列表的 token 总数
     */
    public static int estimateMessagesTokens(List<LlmClient.Message> messages) {
        if (messages == null) return 0;
        long total = 0;
        for (LlmClient.Message msg : messages) {
            total += estimateMessageTokens(msg);
        }
        return saturatingInt(total);
    }

    /** Estimates one complete model-visible message, including tool calls. */
    public static int estimateMessageTokens(LlmClient.Message message) {
        if (message == null) return 0;
        long total = MESSAGE_OVERHEAD
                + estimateText(message.role())
                + estimateText(message.reasoningContent())
                + estimateText(message.toolCallId());
        if (message.contentParts() != null && !message.contentParts().isEmpty()) {
            for (LlmClient.ContentPart part : message.contentParts()) {
                total += CONTENT_PART_OVERHEAD + estimateContentPart(part);
            }
        } else {
            total += estimateText(message.content());
        }
        if (message.toolCalls() != null) {
            for (LlmClient.ToolCall call : message.toolCalls()) {
                if (call == null) continue;
                total += TOOL_CALL_OVERHEAD;
                total += estimateText(call.id());
                if (call.function() != null) {
                    total += estimateText(call.function().name());
                    total += estimateText(call.function().arguments());
                }
            }
        }
        return saturatingInt(total);
    }

    /** Estimates tools schema separately from messages. */
    public static int estimateToolsTokens(List<LlmClient.Tool> tools) {
        if (tools == null || tools.isEmpty()) return 0;
        try {
            return saturatingInt((long) TOOLS_OVERHEAD
                    + MemoryEntry.estimateTokens(JSON.writeValueAsString(tools)));
        } catch (JsonProcessingException e) {
            throw new TokenEstimationException("无法序列化 tools schema", e);
        }
    }

    /** Estimates the exact request envelope used by LlmClient.chat(messages, tools). */
    public static int estimateRequestTokens(
            List<LlmClient.Message> messages,
            List<LlmClient.Tool> tools) {
        return saturatingInt((long) estimateMessagesTokens(messages) + estimateToolsTokens(tools));
    }

    private static int estimateContentPart(LlmClient.ContentPart part) {
        if (part == null) return 0;
        if (part.isText()) return estimateText(part.text());
        if (part.isImage()) {
            return estimateImageTokens(part)
                    + estimateText(part.imageUrl())
                    + estimateText(part.mimeType());
        }
        try {
            return MemoryEntry.estimateTokens(JSON.writeValueAsString(part));
        } catch (JsonProcessingException e) {
            throw new TokenEstimationException("无法序列化未知 content part", e);
        }
    }

    private static int estimateText(String text) {
        return MemoryEntry.estimateTokens(text);
    }

    private static int saturatingInt(long value) {
        if (value <= 0) return 0;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static int estimateImageTokens(LlmClient.ContentPart part) {
        if (part.imageBase64() != null && !part.imageBase64().isBlank()) {
            int bytes = (int) (part.imageBase64().length() * 3L / 4L);
            return Math.max(256, Math.min(4096, bytes / 768));
        }
        return 1024;
    }
}
