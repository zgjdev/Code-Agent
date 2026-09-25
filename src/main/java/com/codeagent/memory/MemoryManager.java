package com.codeagent.memory;

import com.codeagent.context.ContextProfile;
import com.codeagent.llm.LlmClient;

import java.nio.file.Path;
import java.util.List;

/**
 * Memory 管理器 - Memory 系统的门面类。
 *
 * <p>统一管理跨会话长期记忆、混合检索、显式写入关系解析和 token 统计。
 * 当前会话短期上下文仍由 Agent 的 conversationHistory / ParentConversationContext 管理。</p>
 */
public class MemoryManager implements AutoCloseable {
    private final LongTermMemory longTermMemory;
    private final MemoryRetriever retriever;
    private final MemoryRelationClassifier relationClassifier;
    private final MemoryWriteResolver writeResolver;
    private TokenBudget tokenBudget;
    private ContextProfile contextProfile;
    private String currentProject;
    private volatile String submittedUserInput = "";

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, ContextProfile.from(llmClient), null, null, null);
    }

    /** 兼容旧调用签名；短期上下文不再由 MemoryManager 保存。 */
    public MemoryManager(LlmClient llmClient, int ignoredShortTermBudget, int contextWindow) {
        this(llmClient, ignoredShortTermBudget, contextWindow, null);
    }

    /** 兼容旧调用签名；第二个参数不再参与预算计算。 */
    public MemoryManager(LlmClient llmClient, int ignoredShortTermBudget, int contextWindow,
                         LongTermMemory longTermMemory) {
        this(llmClient, ContextProfile.custom(contextWindow), longTermMemory, null, null);
    }

    private MemoryManager(LlmClient llmClient,
                          ContextProfile contextProfile,
                          LongTermMemory longTermMemory,
                          MemoryRetriever injectedRetriever,
                          MemoryRelationClassifier injectedClassifier) {
        this.contextProfile = contextProfile;
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.retriever = injectedRetriever != null
                ? injectedRetriever : new MemoryRetriever(this.longTermMemory);
        this.relationClassifier = injectedClassifier != null
                ? injectedClassifier : new MemoryRelationClassifier(llmClient);
        this.writeResolver = new MemoryWriteResolver(
                this.longTermMemory, this.retriever, this.relationClassifier);
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.currentProject = defaultProjectKey();
    }

    /** 测试/内部接线入口，允许注入 deterministic embedding 和 classifier。 */
    MemoryManager(LlmClient llmClient,
                  ContextProfile contextProfile,
                  LongTermMemory longTermMemory,
                  MemoryRetriever retriever,
                  MemoryRelationClassifier classifier,
                  String projectPath) {
        this(llmClient, contextProfile, longTermMemory, retriever, classifier);
        setProjectPath(projectPath);
    }

    public void setLlmClient(LlmClient llmClient) {
        relationClassifier.setLlmClient(llmClient);
        applyContextProfile(ContextProfile.from(llmClient));
    }

    public void applyContextProfile(ContextProfile contextProfile) {
        this.contextProfile = contextProfile;
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
    }

    public void setProjectPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return;
        }
        this.currentProject = normalizeProjectKey(projectPath);
    }

    /**
     * 设置当前顶层用户真实提交文本，只用于显式长期记忆写入时验证 supersede evidence。
     */
    public void setSubmittedUserInput(String submittedUserInput) {
        this.submittedUserInput = submittedUserInput == null ? "" : submittedUserInput;
    }

    public void storeFact(String fact) {
        storeFact(fact, "project");
    }

    public void storeFact(String fact, String scope) {
        storeFactWithResult(fact, scope, submittedUserInput);
    }

    /** ToolRegistry 使用：返回可直接展示给模型的确定性写入结果。 */
    public String storeFactWithResult(String fact, String scope) {
        return storeFactWithResult(fact, scope, submittedUserInput);
    }

    /**
     * 显式写入入口。submittedInput 必须来自当前用户原始输入；/save 可以直接传命令中的事实文本。
     */
    public String storeFactWithResult(String fact, String scope, String submittedInput) {
        String normalizedScope = normalizeScope(scope);
        MemoryWriteResolver.WriteResult result = writeResolver.resolveAndStore(
                fact, normalizedScope, currentProject, submittedInput);
        return switch (result.action()) {
            case CREATED -> "💾 已保存到长期记忆(" + normalizedScope + "): "
                    + result.memory().getContent();
            case DUPLICATE -> "💾 已确认已有长期记忆(" + normalizedScope + ")，未重复创建: "
                    + result.memory().getContent();
            case SUPERSEDED -> "💾 已更新长期记忆(" + normalizedScope + "): "
                    + result.memory().getContent();
        };
    }

    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        return retriever.retrieveLongTerm(query, limit, currentProject);
    }

    /** list 保留 superseded 条目，方便审计历史。 */
    public List<MemoryEntry> listLongTerm() {
        return longTermMemory.getAll();
    }

    /** 用户可见 search 与 Agent 自动注入共享同一混合排名。 */
    public List<MemoryEntry> searchLongTerm(String query, int limit) {
        return retriever.retrieveLongTerm(query, limit, currentProject);
    }

    public boolean deleteLongTerm(String id) {
        return longTermMemory.delete(id);
    }

    public String buildContextForQuery(String query, int maxTokens) {
        return retriever.buildContextForQuery(query, maxTokens, currentProject);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens, cachedInputTokens);
    }

    public void clearLongTerm() {
        longTermMemory.clear();
    }

    public String getSystemStatus() {
        return "上下文策略: " + contextProfile.summary() + "\n" +
                "短期上下文: 由当前 Agent conversationHistory 维护\n" +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public TokenBudget getTokenBudget() { return tokenBudget; }
    public ContextProfile getContextProfile() { return contextProfile; }
    public String getCurrentProject() { return currentProject; }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "project";
        }
        return "global".equalsIgnoreCase(scope.trim()) ? "global" : "project";
    }

    private static String defaultProjectKey() {
        return normalizeProjectKey(System.getProperty("user.dir"));
    }

    private static String normalizeProjectKey(String path) {
        try {
            Path candidate = Path.of(path).toAbsolutePath().normalize();
            if (java.nio.file.Files.exists(candidate)) {
                return candidate.toRealPath().toString();
            }
            return candidate.toString();
        } catch (Exception e) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }

    @Override
    public void close() {
        retriever.close();
    }
}
