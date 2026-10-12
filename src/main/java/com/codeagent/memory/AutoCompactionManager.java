package com.codeagent.memory;

import com.codeagent.config.ConfigurationValues;

import com.codeagent.llm.LlmClient;

import java.util.List;
import java.util.Locale;

/**
 * 自动压缩协调器：优先使用实验性的会话记忆摘要，失败后回退到完整对话摘要。
 */
public class AutoCompactionManager {
    public static final String SESSION_MEMORY_PROPERTY = "codeagent.compaction.session-memory.enabled";
    public static final String SESSION_MEMORY_ENV = "CODEAGENT_SESSION_MEMORY_COMPACTION_ENABLED";

    private final ConversationHistoryCompactor fullCompactor;
    private final SessionMemoryCompactor sessionMemoryCompactor;

    public AutoCompactionManager(LlmClient llmClient) {
        this(llmClient, sessionMemoryEnabledByConfiguration());
    }

    AutoCompactionManager(LlmClient llmClient, boolean sessionMemoryEnabled) {
        this(new ConversationHistoryCompactor(llmClient),
                new SessionMemoryCompactor(llmClient, sessionMemoryEnabled));
    }

    AutoCompactionManager(
            ConversationHistoryCompactor fullCompactor,
            SessionMemoryCompactor sessionMemoryCompactor) {
        this.fullCompactor = fullCompactor;
        this.sessionMemoryCompactor = sessionMemoryCompactor;
    }

    public void setLlmClient(LlmClient llmClient) {
        fullCompactor.setLlmClient(llmClient);
        sessionMemoryCompactor.setLlmClient(llmClient);
    }

    public Result compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        long estimated = TokenBudget.estimateMessagesTokens(history);
        return compactIfNeeded(history, triggerTokens, estimated);
    }

    public Result compactIfNeeded(
            List<LlmClient.Message> history,
            int triggerTokens,
            long measuredOrPredictedTokens) {
        if (triggerTokens <= 0) return Result.none();
        if (measuredOrPredictedTokens < triggerTokens) return Result.none();
        sessionMemoryCompactor.prepareIfNeeded(history, triggerTokens);
        if (sessionMemoryCompactor.compactIfReady(history, triggerTokens)) {
            return new Result(true, Strategy.SESSION_MEMORY);
        }
        boolean compacted = fullCompactor.compactIfNeeded(history, triggerTokens);
        if (compacted) {
            sessionMemoryCompactor.clear(history);
            return new Result(true, Strategy.FULL_SUMMARY);
        }
        return Result.none();
    }

    /** 手动 /compact 始终使用稳定的完整摘要路径。 */
    public Result compactNow(List<LlmClient.Message> history) {
        sessionMemoryCompactor.clear(history);
        boolean compacted = fullCompactor.compactNow(history);
        return compacted
                ? new Result(true, Strategy.FULL_SUMMARY)
                : Result.none();
    }

    public void clear(List<LlmClient.Message> history) {
        sessionMemoryCompactor.clear(history);
    }

    public boolean isSessionMemoryEnabled() {
        return sessionMemoryCompactor.isEnabled();
    }

    public enum Strategy {
        NONE,
        SESSION_MEMORY,
        FULL_SUMMARY
    }

    public record Result(boolean compacted, Strategy strategy) {
        static Result none() {
            return new Result(false, Strategy.NONE);
        }
    }

    static boolean sessionMemoryEnabledByConfiguration() {
        return truthy(ConfigurationValues.get(SESSION_MEMORY_PROPERTY, SESSION_MEMORY_ENV, null));
    }

    private static boolean truthy(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "1".equals(normalized) || "true".equals(normalized)
                || "yes".equals(normalized) || "on".equals(normalized);
    }

}
