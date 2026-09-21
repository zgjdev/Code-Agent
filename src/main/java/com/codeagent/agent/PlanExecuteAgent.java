package com.codeagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.codeagent.history.ConversationLedger;
import com.codeagent.history.SessionEvent;
import com.codeagent.history.SessionEventDraft;
import com.codeagent.history.SessionStore;
import com.codeagent.context.ContextTokenTracker;
import com.codeagent.context.InvalidationReason;
import com.codeagent.context.RequestSnapshot;
import com.codeagent.context.RequestSnapshotFactory;
import com.codeagent.llm.LlmClient;
import com.codeagent.llm.LlmTraceLogger;
import com.codeagent.lsp.LspDiagnosticReport;
import com.codeagent.memory.AutoCompactionManager;
import com.codeagent.memory.MemoryManager;
import com.codeagent.plan.*;
import com.codeagent.prompt.PromptAssembler;
import com.codeagent.prompt.PromptContext;
import com.codeagent.prompt.PromptMode;
import com.codeagent.prompt.ProjectMemoryLoader;
import com.codeagent.runtime.CancellationContext;
import com.codeagent.skill.SkillContextBuffer;
import com.codeagent.skill.SkillIndexFormatter;
import com.codeagent.skill.SkillRegistry;
import com.codeagent.util.AnsiStyle;
import com.codeagent.tool.ToolRegistry;
import com.codeagent.tool.ToolRegistry.ToolExecutionResult;
import com.codeagent.tool.ToolRegistry.ToolInvocation;
import com.codeagent.tool.TurnToolPolicy;
import com.codeagent.tool.ToolResourceScope;
import com.codeagent.util.TerminalMarkdownRenderer;
import com.codeagent.image.ImageReferenceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行
 */
public class PlanExecuteAgent {
    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private record PlanRunOutcome(String result, boolean persistAssistantMessage) {
        static PlanRunOutcome executed(String result) {
            return new PlanRunOutcome(result, true);
        }

        static PlanRunOutcome canceled(String result) {
            return new PlanRunOutcome(result, false);
        }

        static PlanRunOutcome failed(String result) {
            return new PlanRunOutcome(result, true);
        }
    }

    private record TaskRunResult(String result, boolean streamedOutput,
                                 TurnToolPolicy.TrustedUrlContext trustedUrls,
                                 TaskVerificationReport verificationReport) {
        static TaskRunResult of(String result, boolean streamedOutput, TurnToolPolicy policy,
                                TaskVerificationReport verificationReport) {
            return new TaskRunResult(result, streamedOutput, policy.trustedUrlContext(), verificationReport);
        }

        TaskRunResult withReport(TaskVerificationReport report) {
            return new TaskRunResult(result, streamedOutput, trustedUrls, report);
        }
    }

    private record TaskExecutionResult(Task task, String result, boolean streamedOutput,
                                       TurnToolPolicy.TrustedUrlContext trustedUrls,
                                       TaskVerificationReport verificationReport, Exception error) {
        static TaskExecutionResult success(Task task, TaskRunResult taskRunResult) {
            return new TaskExecutionResult(task, taskRunResult.result(), taskRunResult.streamedOutput(),
                    taskRunResult.trustedUrls(), taskRunResult.verificationReport(), null);
        }

        static TaskExecutionResult failure(Task task, Exception error) {
            return new TaskExecutionResult(task, null, false, null, null, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    public enum PlanReviewAction {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final PlanReviewHandler reviewHandler;
    private final MemoryManager memoryManager;
    private final AutoCompactionManager autoCompactionManager;
    private final ContextTokenTracker contextTokenTracker = new ContextTokenTracker();
    private final RequestSnapshotFactory requestSnapshotFactory = new RequestSnapshotFactory();
    private long historyVersion;
    private final PrintStream out;
    private ConversationLedger conversationLedger = ConversationLedger.disabled();
    private SessionStore.SessionHandle parentSession;
    private final ThreadLocal<SessionStore.SessionHandle> childSession = new ThreadLocal<>();
    private PlanStateStore planStateStore;
    private final Set<String> interruptedRecoveryTaskIds = ConcurrentHashMap.newKeySet();
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private TurnToolPolicy turnToolPolicy = TurnToolPolicy.forExplicitTask("");
    private String submittedPolicyInput = "";
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private static final int MAX_RETRIES_PER_STEP = 2;
    private static final int MAX_REPLANS_PER_RUN = 1;
    private final PipelineOptions pipelineOptions;

    public PlanExecuteAgent(LlmClient llmClient) {
        this(llmClient, (goal, plan) -> PlanReviewDecision.execute());
    }

    public PlanExecuteAgent(LlmClient llmClient, PlanReviewHandler reviewHandler) {
        this(llmClient, new ToolRegistry(), null, null, reviewHandler);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler,
                            PrintStream out) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler, out);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler,
                            PrintStream out, PipelineOptions pipelineOptions) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler, out, pipelineOptions);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, planner, memoryManager, reviewHandler, null);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler, PrintStream out) {
        this(llmClient, toolRegistry, planner, memoryManager, reviewHandler, out,
                PipelineOptions.PLAN_PRESET);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler, PrintStream out,
                     PipelineOptions pipelineOptions) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry != null ? toolRegistry : new ToolRegistry();
        this.out = out == null ? deferredSystemOut() : out;
        this.planner = planner != null ? planner : new Planner(llmClient, this.out);
        this.reviewHandler = reviewHandler == null ? (goal, plan) -> PlanReviewDecision.execute() : reviewHandler;
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager(llmClient);
        this.pipelineOptions = pipelineOptions != null ? pipelineOptions : PipelineOptions.PLAN_PRESET;
        this.autoCompactionManager = new AutoCompactionManager(llmClient);
        this.toolRegistry.setContextProfile(this.memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(this.memoryManager::storeFact);
        this.planner.setProjectMemorySupplier(this::buildProjectMemoryContext);
    }

    private static PrintStream deferredSystemOut() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                System.out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                System.out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                System.out.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public void setConversationLedger(ConversationLedger conversationLedger) {
        this.conversationLedger = conversationLedger == null
                ? ConversationLedger.disabled()
                : conversationLedger;
        this.planner.setConversationLedger(this.conversationLedger);
    }

    ConversationLedger getConversationLedger() {
        return conversationLedger;
    }

    public void setParentSession(SessionStore.SessionHandle parentSession) {
        this.parentSession = parentSession;
    }

    public void setPlanStateStore(PlanStateStore planStateStore) {
        this.planStateStore = planStateStore;
    }

    public void enableDefaultPlanStateStore() {
        this.planStateStore = openPlanStateStoreSafely();
    }

    private PlanStateStore openPlanStateStoreSafely() {
        try {
            return PlanStateStore.openDefault();
        } catch (Exception e) {
            log.warn("Plan state persistence unavailable; continuing in memory-only mode", e);
            return null;
        }
    }

    private Path planWorkspace() {
        return Path.of(toolRegistry.getProjectPath()).toAbsolutePath().normalize();
    }

    private String currentSessionId() {
        return parentSession == null ? null : parentSession.sessionId();
    }

    private Optional<PlanStateStore.ActivePlanInfo> findActivePlanInfo() {
        String sessionId = currentSessionId();
        if (planStateStore == null || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        try {
            return planStateStore.findActiveInfo(planWorkspace(), sessionId);
        } catch (SQLException e) {
            log.warn("Failed to inspect active plan for session {}", sessionId, e);
            return Optional.empty();
        }
    }

    private Optional<PlanStateStore.ResumeCandidate> loadActivePlanForResume() {
        String sessionId = currentSessionId();
        if (planStateStore == null || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        try {
            return planStateStore.findActive(planWorkspace(), sessionId);
        } catch (SQLException e) {
            log.warn("Failed to load active plan for session {}", sessionId, e);
            return Optional.empty();
        }
    }

    private void savePlanSafely(ExecutionPlan plan) {
        String sessionId = currentSessionId();
        if (planStateStore == null || plan == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            planStateStore.savePlan(planWorkspace(), sessionId, submittedPolicyInput, plan);
        } catch (SQLException e) {
            log.warn("Failed to save plan {} for session {}", plan.getId(), sessionId, e);
        }
    }

    private void checkpointPlanSafely(ExecutionPlan plan) {
        if (planStateStore == null || plan == null) {
            return;
        }
        try {
            planStateStore.checkpointPlan(plan);
        } catch (SQLException e) {
            log.warn("Failed to checkpoint plan {}", plan.getId(), e);
        }
    }

    private void checkpointTaskSafely(String planId, Task task) {
        if (planStateStore == null || planId == null || task == null) {
            return;
        }
        try {
            planStateStore.checkpointTask(planId, task);
        } catch (SQLException e) {
            log.warn("Failed to checkpoint plan task {}:{}", planId, task.getId(), e);
        }
    }

    private boolean maybeCompactHistory(List<LlmClient.Message> messages,
                                        PrintStream out,
                                        String actor,
                                        ContextTokenTracker.ContextPrediction prediction) {
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        int beforeMessages = messages.size();
        try {
            AutoCompactionManager.Result result;
            if (prediction.mode() == ContextTokenTracker.Mode.NONE) {
                result = autoCompactionManager.compactIfNeeded(messages, trigger);
            } else if (prediction.effectiveTokens() < trigger) {
                return false;
            } else {
                result = autoCompactionManager.compactIfNeeded(
                        messages, trigger, prediction.effectiveTokens());
            }
            if (result.compacted()) {
                historyVersion++;
                contextTokenTracker.invalidate(InvalidationReason.COMPACTION);
                conversationLedger.appendEvent(
                        "compaction",
                        "plan",
                        actor,
                        "automatic",
                        Map.of(
                                "beforeMessages", beforeMessages,
                                "afterMessages", messages.size(),
                                "strategy", result.strategy().name().toLowerCase()));
                if (out != null) {
                    out.println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
                }
                return true;
            }
        } catch (Exception e) {
            log.warn("request snapshot/compaction failed; using legacy estimate fallback", e);
            try {
                AutoCompactionManager.Result fallback = autoCompactionManager
                        .compactIfNeeded(messages, trigger);
                if (fallback.compacted()) {
                    historyVersion++;
                    contextTokenTracker.invalidate(InvalidationReason.COMPACTION);
                    return true;
                }
            } catch (Exception fallbackError) {
                log.warn("conversationHistory legacy compaction fallback failed", fallbackError);
            }
        }
        return false;
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("Failed to build skill index", e);
            return "";
        }
    }

    private String prependSkillBodies(String content) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return content;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return content;
        return drained + "\n" + content;
    }

    /**
     * 运行任务（自动判断是否需要规划）
     */
    public String run(String userInput) {
        return run(userInput, userInput);
    }

    /** Use submittedUserInput for policy decisions and userInput for expanded task context. */
    public String run(String userInput, String submittedUserInput) {
        log.info("Plan run started: inputLength={}", userInput == null ? 0 : userInput.length());
        submittedPolicyInput = submittedUserInput == null ? "" : submittedUserInput;
        turnToolPolicy = TurnToolPolicy.fromUserInput(
                submittedUserInput,
                toolRegistry.isSharedBrowserSession(),
                toolRegistry.hasAgentOwnedCurrentBrowserPage());
        conversationLedger.appendMessage(
                "plan", "plan-agent", "user_input", LlmClient.Message.user(userInput));
        StreamState streamState = new StreamState();
        try {
            if (CancellationContext.isCancelled()) {
                conversationLedger.appendEvent(
                        "run_cancelled", "plan", "plan-agent", "before_planning", Map.of());
                return "⏹️ 已取消当前计划执行。";
            }
            PlanRunOutcome outcome = runWithPlan(userInput, streamState);
            if (outcome.persistAssistantMessage() && outcome.result() != null && !outcome.result().isBlank()) {
                conversationLedger.appendMessage(
                        "plan",
                        "plan-agent",
                        "run_result",
                        LlmClient.Message.assistant(outcome.result()));
            }
            if (streamState.hasStreamedOutput() && (outcome.result() == null || outcome.result().isBlank())) {
                return "";
            }
            return outcome.result();
        } catch (Exception e) {
            log.error("Plan run failed", e);
            String errorMessage = "❌ 执行失败: " + e.getMessage();
            conversationLedger.appendMessage(
                    "plan",
                    "plan-agent",
                    "run_error",
                    LlmClient.Message.assistant(errorMessage));
            return errorMessage;
        }
    }

/**
     * 使用Plan-and-Execute模式执行
     */
    private PlanRunOutcome runWithPlan(String goal, StreamState streamState) throws IOException {
        Optional<PlanStateStore.ActivePlanInfo> activePlan = findActivePlanInfo();
        if (activePlan.isPresent()) {
            PlanStateStore.ActivePlanInfo existing = activePlan.get();
            return PlanRunOutcome.failed(
                    "⚠️ 当前会话已有未完成计划 " + existing.planId()
                            + "。使用 /plan resume 继续，或 /plan abandon 放弃后再创建新计划。");
        }

        interruptedRecoveryTaskIds.clear();
        ExecutionPlan plan = planner.createPlan(goal);
        return reviewAndExecutePlan(plan, streamState, 0);
    }

    public Optional<ActivePlanInfo> activePlanInfo() {
        return findActivePlanInfo().map(info -> new ActivePlanInfo(
                info.planId(),
                info.goal(),
                info.status(),
                info.completedTasks(),
                info.totalTasks()));
    }

    public String resumeActivePlan() {
        String sessionId = currentSessionId();
        if (planStateStore == null || sessionId == null || sessionId.isBlank()) {
            return "⚠️ 当前没有可绑定的持久化 Session，无法恢复 Plan。";
        }

        Optional<PlanStateStore.ResumeCandidate> candidateOptional = loadActivePlanForResume();
        if (candidateOptional.isEmpty()) {
            return "ℹ️ 当前 Session 没有未完成 Plan。";
        }

        PlanStateStore.ResumeCandidate candidate = candidateOptional.get();
        interruptedRecoveryTaskIds.clear();
        interruptedRecoveryTaskIds.addAll(candidate.interruptedTaskIds());
        submittedPolicyInput = candidate.policyInput();
        turnToolPolicy = TurnToolPolicy.forExplicitTask(
                submittedPolicyInput,
                toolRegistry.isSharedBrowserSession(),
                toolRegistry.hasAgentOwnedCurrentBrowserPage());

        out.println("♻️ 恢复计划 " + candidate.plan().getId()
                + "，将跳过已完成节点并继续调度。\n");
        try {
            return executePlan(candidate.plan(), new StreamState(), 0);
        } catch (Exception e) {
            log.error("Plan resume failed: {}", candidate.plan().getId(), e);
            return "❌ 恢复计划失败: " + e.getMessage();
        }
    }

    public String abandonActivePlan() {
        String sessionId = currentSessionId();
        if (planStateStore == null || sessionId == null || sessionId.isBlank()) {
            return "⚠️ 当前没有可绑定的持久化 Session，无法放弃 Plan。";
        }
        try {
            boolean abandoned = planStateStore.abandonActive(planWorkspace(), sessionId);
            interruptedRecoveryTaskIds.clear();
            return abandoned
                    ? "✅ 已放弃当前 Session 的未完成 Plan。"
                    : "ℹ️ 当前 Session 没有未完成 Plan。";
        } catch (SQLException e) {
            log.warn("Failed to abandon active plan for session {}", sessionId, e);
            return "❌ 放弃 Plan 失败: " + e.getMessage();
        }
    }

    public record ActivePlanInfo(String planId,
                                 String goal,
                                 ExecutionPlan.PlanStatus status,
                                 int completedTasks,
                                 int totalTasks) {
    }

    private PlanRunOutcome reviewAndExecutePlan(ExecutionPlan plan, StreamState streamState,
                                                int replanDepth) throws IOException {
        while (true) {
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                savePlanSafely(plan);
                return PlanRunOutcome.executed(executePlan(plan, streamState, replanDepth));
            }

            if (decision.action() == PlanReviewAction.CANCEL) {
                plan.setStatus(ExecutionPlan.PlanStatus.CANCELLED);
                checkpointPlanSafely(plan);
                return PlanRunOutcome.canceled("⏹️ 已取消本次计划执行。");
            }

            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return PlanRunOutcome.executed(executePlan(plan, streamState, replanDepth));
            }

            out.println("📝 已收到补充要求，正在重新规划...\n");
            plan.setStatus(ExecutionPlan.PlanStatus.CANCELLED);
            checkpointPlanSafely(plan);
            interruptedRecoveryTaskIds.clear();
            String revisedGoal = plan.getGoal() + "\n补充要求：" + feedback;
            submittedPolicyInput = submittedPolicyInput + "\n补充要求：" + feedback;
            turnToolPolicy = TurnToolPolicy.fromUserInput(
                    submittedPolicyInput,
                    toolRegistry.isSharedBrowserSession(),
                    toolRegistry.hasAgentOwnedCurrentBrowserPage());
            plan = planner.createPlan(revisedGoal);
        }
    }

    private String executePlan(ExecutionPlan plan, StreamState streamState, int replanDepth)
            throws IOException {
        log.info("Executing plan: goal='{}', taskCount={}", plan.getGoal(), plan.getAllTasks().size());
        out.println("🚀 开始执行计划...\n");

        plan.markStarted();
        checkpointPlanSafely(plan);
        StringBuilder finalResult = new StringBuilder();
        Map<String, Boolean> streamedTaskOutputs = new HashMap<>();
        Map<String, TurnToolPolicy.TrustedUrlContext> taskTrustedUrls = new HashMap<>();

        while (true) {
            if (CancellationContext.isCancelled()) {
                plan.setStatus(ExecutionPlan.PlanStatus.CANCELLED);
                checkpointPlanSafely(plan);
                return "⏹️ 已取消当前计划执行。";
            }
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            List<Task> selectedTasks = new ConflictAwareBatchSelector().select(executableTasks, 4);
            List<TaskExecutionResult> batchResults = executeTaskBatch(
                    plan, selectedTasks, streamState, taskTrustedUrls);
            for (TaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();

                if (!batchResult.failed()
                        && (batchResult.verificationReport() == null
                        || batchResult.verificationReport().outcome() == VerificationOutcome.VERIFIED
                        || batchResult.verificationReport().outcome() == VerificationOutcome.NOT_REQUIRED)) {
                    task.markCompleted(batchResult.result());
                    checkpointTaskSafely(plan.getId(), task);
                    interruptedRecoveryTaskIds.remove(task.getId());
                    taskTrustedUrls.put(task.getId(), batchResult.trustedUrls());
                    streamedTaskOutputs.put(task.getId(), batchResult.streamedOutput());
                    log.info("Task completed: {} status={} resultChars={}",
                            task.getId(), task.getStatus(), batchResult.result() == null ? 0 : batchResult.result().length());
                    if (batchResult.streamedOutput() || batchResult.result() == null || batchResult.result().isBlank()) {
                        out.println("✅ 完成 [" + task.getId() + "]\n");
                    } else {
                        out.println("✅ 完成 [" + task.getId() + "]: "
                                + batchResult.result().substring(0, Math.min(100, batchResult.result().length())) + "\n");
                    }
                    continue;
                }

                if (!batchResult.failed()) {
                    String reason = batchResult.verificationReport() == null
                            ? "verification unavailable"
                            : String.join("; ", batchResult.verificationReport().blockingReasons());
                    task.markUnverified(reason);
                    checkpointTaskSafely(plan.getId(), task);
                    interruptedRecoveryTaskIds.remove(task.getId());
                    finalResult.append("任务 ").append(task.getId()).append(" 未验证: ").append(reason);
                    out.println("⚠️ 未验证 [" + task.getId() + "]: " + reason + "\n");
                    continue;
                }

                Exception error = batchResult.error();
                task.markFailed(error.getMessage());
                checkpointTaskSafely(plan.getId(), task);
                interruptedRecoveryTaskIds.remove(task.getId());
                log.warn("Task failed: {} error={}", task.getId(), error.getMessage());
                out.println("❌ 失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                if (!finalResult.isEmpty()) {
                    finalResult.append("\n");
                }
                finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(error.getMessage());

                if (plan.getProgress() < 0.5) {
                    if (replanDepth >= MAX_REPLANS_PER_RUN) {
                        out.println("⚠️ 已达到最大重规划次数，保留当前结果\n");
                        continue;
                    }
                    out.println("🔄 尝试重新规划...\n");
                    plan.markFailed();
                    checkpointPlanSafely(plan);
                    interruptedRecoveryTaskIds.clear();
                    ExecutionPlan replanned = planner.replan(plan, error.getMessage());
                    return "⚠️ 原计划有任务失败，已按重规划结果继续执行。\n"
                            + finalResult + "\n"
                            + reviewAndExecutePlan(replanned, streamState, replanDepth + 1).result();
                }
            }
        }

        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            checkpointPlanSafely(plan);
            return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";
        }

        String planSummary = finalResult.isEmpty()
                ? buildFinalResult(plan, streamedTaskOutputs)
                : finalResult.toString();

        if (plan.hasFailed()) {
            plan.markFailed();
            checkpointPlanSafely(plan);
            if (planSummary.isBlank()) {
                return "⚠️ 计划部分完成，有任务失败。";
            }
            return "⚠️ 计划部分完成，有任务失败。\n" + planSummary;
        }

        plan.markCompleted();
        checkpointPlanSafely(plan);
        if (planSummary.isBlank()) {
            return "✅ 计划执行完成！";
        }
        return "✅ 计划执行完成！\n" + planSummary;
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks,
                                                       StreamState streamState,
                                                       Map<String, TurnToolPolicy.TrustedUrlContext> taskTrustedUrls) {
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            log.info("Executing single task: {} type={}", task.getId(), task.getType());
            out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();
            checkpointTaskSafely(plan.getId(), task);

            try {
                return List.of(TaskExecutionResult.success(task, executeTask(
                        plan.getGoal(), plan, task, streamState, out, taskTrustedUrls)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        log.info("Executing parallel batch: {}", parallelTaskIds);
        out.println("⚡ 本轮并行执行 " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4), r -> {
            Thread t = new Thread(r, "codeagent-plan-executor");
            t.setDaemon(true);
            return t;
        });
        try {
            Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
            List<Future<TaskExecutionResult>> futures = new ArrayList<>();
            for (Task task : executableTasks) {
                out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                checkpointTaskSafely(plan.getId(), task);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                buffers.put(task.getId(), baos);
                PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
                futures.add(executor.submit(() -> {
                    try {
                        return TaskExecutionResult.success(task, executeTask(
                                plan.getGoal(), plan, task, streamState, taskOut, taskTrustedUrls));
                    } catch (Exception e) {
                        return TaskExecutionResult.failure(task, e);
                    }
                }));
            }

            List<TaskExecutionResult> results = new ArrayList<>();
            for (Future<TaskExecutionResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Exception error = cause instanceof Exception exception
                            ? exception
                            : new RuntimeException(cause);
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), error));
                }
            }

            // 按任务顺序 flush 各缓冲区到 stdout，避免并行输出交错
            for (Task task : executableTasks) {
                ByteArrayOutputStream buf = buffers.get(task.getId());
                if (buf != null && buf.size() > 0) {
                    out.print(buf.toString(StandardCharsets.UTF_8));
                    out.flush();
                }
            }

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 执行单个任务（支持多轮工具调用）
     */
    private TaskRunResult executeTask(String goal, ExecutionPlan plan, Task task,
                                      StreamState streamState, PrintStream out,
                                      Map<String, TurnToolPolicy.TrustedUrlContext> taskTrustedUrls) throws IOException {
        List<TurnToolPolicy.TrustedUrlContext> dependencyUrls = task.getDependencies().stream()
                .map(taskTrustedUrls::get)
                .filter(Objects::nonNull)
                .toList();
        TurnToolPolicy taskToolPolicy = turnToolPolicy.forkWithTrustedUrls(dependencyUrls)
                .restrictTo(toResourceScope(task));
        TaskEvidenceCollector evidenceCollector = new TaskEvidenceCollector();
        SessionStore.SessionHandle child = null;
        try {
            if (parentSession != null) {
                child = parentSession.createChild("plan", "task:" + task.getId());
                childSession.set(child);
            }
            String recoveryFeedback = interruptedRecoveryTaskIds.contains(task.getId())
                    ? "该任务来自进程中断恢复。请先检查当前 workspace / 外部资源状态和已有产物，"
                    + "不要假设上次副作用未发生；确认缺失工作后再继续，优先重新验证而不是重复写入。"
                    : null;
            TaskRunResult result = executeTaskWithPolicy(
                    goal, plan, task, streamState, out, dependencyUrls, recoveryFeedback,
                    taskToolPolicy, evidenceCollector);
            result = result.withReport(DeterministicEvidenceGate.evaluate(task, evidenceCollector.snapshot()));
            int evidenceRetries = 0;
            while (result.verificationReport().outcome() == VerificationOutcome.REJECTED
                    && evidenceRetries < MAX_RETRIES_PER_STEP) {
                evidenceRetries++;
                result = executeTaskWithPolicy(goal, plan, task, streamState, out, dependencyUrls,
                        String.join("; ", result.verificationReport().blockingReasons()),
                        taskToolPolicy, evidenceCollector);
                result = result.withReport(DeterministicEvidenceGate.evaluate(task, evidenceCollector.snapshot()));
            }
            if (pipelineOptions.stepReview()) {
                task.markReviewing();
                checkpointTaskSafely(plan.getId(), task);
                result = applyStepReview(
                        goal, plan, task, streamState, out, dependencyUrls, taskToolPolicy,
                        evidenceCollector, result);
            }
            if (child != null) {
                parentSession.recordChildResult(child, result.result(), "completed");
                child.markClosed("completed");
            }
            return result;
        } finally {
            childSession.remove();
            if (child != null) child.close();
            taskToolPolicy.releaseBrowserLease();
        }
    }

    private ToolResourceScope toResourceScope(Task task) {
        Path root = Path.of(toolRegistry.getProjectPath()).toAbsolutePath().normalize();
        TaskResourceClaims claims = task.getResourceClaims();
        List<Path> reads = claims.readPaths().stream().map(root::resolve).toList();
        List<Path> writes = claims.writePaths().stream()
                .map(path -> root.resolve(path.endsWith("/") ? path.substring(0, path.length() - 1) : path))
                .toList();
        return new ToolResourceScope(root, reads, writes, claims.workspaceWrite());
    }

    private TaskRunResult applyStepReview(String goal, ExecutionPlan plan, Task task,
                                          StreamState streamState, PrintStream out,
                                          List<TurnToolPolicy.TrustedUrlContext> dependencyUrls,
                                          TurnToolPolicy taskToolPolicy,
                                          TaskEvidenceCollector evidenceCollector,
                                          TaskRunResult initial) throws IOException {
        // 每个任务独占一个 Reviewer：并行批次最多 4 个任务同时进来，
        // 共享实例会让多条线程写同一份 SubAgent 会话历史。
        StepReviewer reviewer = new SubAgentStepReviewer(
                new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, toolRegistry), out);
        TaskRunResult result = initial;
        int retries = 0;
        while (true) {
            StepReviewDecision decision = reviewer.review(new StepReviewRequest(
                    goal, task, result.result(), result.verificationReport()));
            if (decision.approved()) {
                return result;
            }
            if (retries >= MAX_RETRIES_PER_STEP) {
                TaskVerificationReport unavailable = new TaskVerificationReport(
                        VerificationOutcome.UNAVAILABLE, task.getAcceptanceCriteria(),
                        result.verificationReport() == null ? List.of() : result.verificationReport().evidence(),
                        List.of(decision.outcome() == StepReviewDecision.ReviewOutcome.UNAVAILABLE
                                ? "reviewer unavailable" : "review retries exhausted"));
                result = result.withReport(unavailable);
                out.println("⚠️ 任务 [" + task.getId() + "] 达到最大重试次数，保留当前结果\n");
                return result;
            }
            retries++;
            out.println("⚠️ 任务 [" + task.getId() + "] 审查未通过，重新执行...");
            out.println("   反馈: " + decision.feedback() + "\n");
            result = executeTaskWithPolicy(goal, plan, task, streamState, out, dependencyUrls,
                    decision.feedback(), taskToolPolicy, evidenceCollector);
            result = result.withReport(DeterministicEvidenceGate.evaluate(task, evidenceCollector.snapshot()));
        }
    }

    private TaskRunResult executeTaskWithPolicy(
            String goal, ExecutionPlan plan, Task task, StreamState streamState, PrintStream out,
            List<TurnToolPolicy.TrustedUrlContext> dependencyUrls,
            String retryFeedback,
            TurnToolPolicy taskToolPolicy,
            TaskEvidenceCollector evidenceCollector) throws IOException {
        String prompt = promptAssembler.assemble(PromptMode.PLAN, PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .variable("taskType", task.getType())
                .variable("taskDescription", task.getDescription())
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build());

        // 注入长期记忆上下文
        String memoryContext = memoryManager.buildContextForQuery(
                task.getDescription(),
                memoryManager.getContextProfile().memoryContextTokens());
        String taskInput = buildStepBriefing(goal, plan, task, dependencyUrls, retryFeedback).render();
        if (!memoryContext.isEmpty()) {
            taskInput = taskInput + "\n\n" + memoryContext;
        }
        taskInput = prependSkillBodies(taskInput);

        String actor = "task:" + task.getId();
        List<LlmClient.Message> messages = new ArrayList<>();
        appendTaskMessage(messages, actor, "system_prompt", LlmClient.Message.system(prompt));
        appendTaskMessage(
                messages,
                actor,
                "task_input",
                ImageReferenceParser.userMessage(
                        taskInput,
                        Path.of(toolRegistry.getProjectPath())));

        StringBuilder allResults = new StringBuilder();
        TaskStreamRenderer streamRenderer = new TaskStreamRenderer(task.getId(), streamState, out);
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);

        while (true) {
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。",
                        streamRenderer.hasStreamedOutput(), taskToolPolicy, null);
            }

            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                return finalizePartialTask(
                        task,
                        messages,
                        actor,
                        exitReason,
                        budget,
                        allResults,
                        streamRenderer,
                        taskToolPolicy,
                        evidenceCollector,
                        out);
            }
            int iteration = budget.beginIteration();

            // 冻结最终 tools exposure 后预测完整请求；只有快照/计量异常才回退旧估算。
            injectPendingLspDiagnostics(messages, out, actor, evidenceCollector);
            List<LlmClient.Tool> toolDefinitions = llmClient.supportsTools()
                    ? toolRegistry.getToolDefinitions()
                    : null;
            TurnToolPolicy.ToolExposure toolExposure = taskToolPolicy.expose(toolDefinitions);
            RequestSnapshot requestSnapshot;
            ContextTokenTracker.ContextPrediction prediction;
            try {
                requestSnapshot = requestSnapshotFactory.capture(
                        llmClient, messages, toolExposure.definitions(), historyVersion);
                prediction = contextTokenTracker.predict(requestSnapshot);
            } catch (Exception snapshotError) {
                log.warn("plan request snapshot failed; using legacy estimate fallback", snapshotError);
                requestSnapshot = null;
                prediction = new ContextTokenTracker.ContextPrediction(
                        0, Integer.MAX_VALUE, 0, 0, 0,
                        ContextTokenTracker.Mode.NONE, false, "snapshot failed");
            }
            if (maybeCompactHistory(messages, out, actor, prediction)) {
                requestSnapshot = requestSnapshotFactory.capture(
                        llmClient, messages, toolExposure.definitions(), historyVersion);
                prediction = contextTokenTracker.predict(requestSnapshot);
            }
            LlmClient.ChatResponse response = llmClient.chat(
                    messages,
                    toolExposure.definitions(),
                    streamRenderer
            );
            LlmTraceLogger.logReasoning(log,
                    "plan-task task=" + task.getId() + " iteration=" + iteration,
                    llmClient,
                    response.reasoningContent());
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。",
                        streamRenderer.hasStreamedOutput(), taskToolPolicy, null);
            }

            budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
            LlmClient.Message assistantMessage = LlmClient.Message.assistant(
                    response.reasoningContent(), response.content(), response.toolCalls());
            if (requestSnapshot != null) {
                contextTokenTracker.recordSuccessfulCall(
                        requestSnapshot,
                        com.codeagent.memory.TokenBudget.estimateMessageTokens(assistantMessage),
                        llmClient.normalizeUsage(response));
            }

            log.info("Task {} iteration {} response: toolCalls={}, reasoningChars={}, contentChars={}",
                    task.getId(),
                    iteration,
                    response.toolCalls() == null ? 0 : response.toolCalls().size(),
                    response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                    response.content() == null ? 0 : response.content().length());

            if (!response.hasToolCalls()) {
                conversationLedger.appendMessage(
                        "plan",
                        actor,
                        "llm_response",
                        LlmClient.Message.assistant(response.reasoningContent(), response.content()));
                persistChildMessage(LlmClient.Message.assistant(
                        response.reasoningContent(), response.content()), "llm_response");
                memoryManager.recordTokenUsage(
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        budget.totalCachedInputTokens());
                if (!allResults.isEmpty() && (response.content() == null || response.content().isBlank())) {
                    String toolOnlyResult = allResults.toString().trim();
                    streamRenderer.finish();
                    return TaskRunResult.of(toolOnlyResult, streamRenderer.hasStreamedOutput(), taskToolPolicy, null);
                }
                streamRenderer.finish();
                return TaskRunResult.of(response.content(), streamRenderer.hasStreamedOutput(), taskToolPolicy, null);
            }

            // 有工具调用：执行工具并将结果回灌到消息历史
            budget.recordToolCalls(response.toolCalls());
            printToolCalls(out, taskToolPolicy.visibleToolCalls(response.toolCalls(), toolExposure));
            appendTaskMessage(messages, actor, "llm_response", LlmClient.Message.assistant(
                    response.reasoningContent(),
                    response.content(),
                    response.toolCalls()
            ));

            // 在工具执行前 flush 并重置流式渲染器：避免 Markdown renderer pending 文本
            // 被 HITL 提示"跨过"导致 🧠 / 🤖 标题与内容错位
            streamRenderer.resetBetweenIterations();

            List<ToolExecutionResult> toolResults = executeToolCalls(
                    task.getId(), response.toolCalls(), taskToolPolicy, toolExposure, evidenceCollector);
            for (ToolExecutionResult toolResult : toolResults) {
                allResults.append(toolResult.result()).append("\n");
                appendTaskMessage(
                        messages,
                        actor,
                        "tool_execution",
                        LlmClient.Message.tool(toolResult.id(), toolResult.result()));
            }
            appendImageToolMessages(messages, toolResults, actor);
        }
    }

    /** 命中显式预算后，以一次无工具调用整理计划任务的部分成果。 */
    private TaskRunResult finalizePartialTask(
            Task task,
            List<LlmClient.Message> messages,
            String actor,
            AgentBudget.ExitReason exitReason,
            AgentBudget budget,
            StringBuilder allResults,
            TaskStreamRenderer streamRenderer,
            TurnToolPolicy taskToolPolicy,
            TaskEvidenceCollector evidenceCollector,
            PrintStream out) {
        String description = budget.describeExit(exitReason);
        log.warn("Plan task {} exhausted budget: reason={}, iteration={}, tokens={}/{}",
                task.getId(), exitReason, budget.iteration(),
                budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
        appendTaskMessage(
                messages,
                actor,
                "budget_finalization",
                LlmClient.Message.user(budget.finalizationInstruction(exitReason)));
        out.println(AnsiStyle.section("⚠️ 任务 [" + task.getId() + "] 执行预算已触发，正在整理部分结果"));

        String content;
        try {
            LlmClient.ChatResponse response = llmClient.chat(messages, List.of(), streamRenderer);
            budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
            content = response.content() == null ? "" : response.content().trim();
            appendTaskMessage(
                    messages,
                    actor,
                    "budget_finalization_response",
                    LlmClient.Message.assistant(response.reasoningContent(), response.content()));
        } catch (IOException e) {
            log.error("Plan task {} finalization call failed after budget exhaustion", task.getId(), e);
            content = "收尾调用失败：" + e.getMessage();
        }

        if (content.isBlank()) {
            content = allResults.toString().trim();
        }
        String partialResult = formatPartialResult(description, content);
        memoryManager.recordTokenUsage(
                budget.totalInputTokens(),
                budget.totalOutputTokens(),
                budget.totalCachedInputTokens());
        streamRenderer.finish();
        return TaskRunResult.of(partialResult, streamRenderer.hasStreamedOutput(), taskToolPolicy, null);
    }

    private String formatPartialResult(String description, String content) {
        String heading = "⚠️ 部分完成（" + description + "）";
        return content == null || content.isBlank() ? heading : heading + "\n\n" + content;
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context for plan task", e);
            return "";
        }
    }

    private String buildProjectMemoryContext() {
        try {
            return ProjectMemoryLoader.createDefault(Path.of(toolRegistry.getProjectPath())).loadForPrompt();
        } catch (Exception e) {
            log.warn("Failed to load CODEAGENT.md project memory", e);
            return "";
        }
    }

    private void injectPendingLspDiagnostics(List<LlmClient.Message> messages, PrintStream out,
                                             String actor, TaskEvidenceCollector evidenceCollector) {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        evidenceCollector.observeLsp(report);
        appendTaskMessage(
                messages,
                actor,
                "lsp_diagnostics",
                LlmClient.Message.user(report.promptText()));
        out.println(report.displayText());
        log.info("Injected LSP diagnostics into plan task conversation");
    }

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private List<ToolExecutionResult> executeToolCalls(String taskId,
                                                       List<LlmClient.ToolCall> toolCalls,
                                                       TurnToolPolicy taskToolPolicy,
                                                       TurnToolPolicy.ToolExposure toolExposure,
                                                       TaskEvidenceCollector evidenceCollector) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Task {} scheduling tool {}", taskId, toolName);
            log.debug("Task {} tool args [{}]: {}", taskId, toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Task {} executing {} tool calls in parallel", taskId, invocations.size());
        }
        List<ToolExecutionResult> results = taskToolPolicy.execute(toolRegistry, invocations, toolExposure);
        evidenceCollector.observeTools(invocations, results, Path.of(toolRegistry.getProjectPath()));
        for (ToolExecutionResult result : results) {
            log.debug("Task {} tool result preview [{}]: {}", taskId, result.name(), preview(result.result(), 300));
        }
        return results;
    }

    private void appendImageToolMessages(List<LlmClient.Message> messages,
                                         List<ToolExecutionResult> toolResults,
                                         String actor) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        for (ToolExecutionResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text("工具 " + result.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(result.imageParts());
            appendTaskMessage(
                    messages,
                    actor,
                    "image_tool_result",
                    LlmClient.Message.user(parts));
        }
    }

    private void appendTaskMessage(List<LlmClient.Message> messages, String actor,
                                   String source, LlmClient.Message message) {
        messages.add(message);
        historyVersion++;
        conversationLedger.appendMessage("plan", actor, source, message);
        persistChildMessage(message, source);
    }

    private void persistChildMessage(LlmClient.Message message, String source) {
        SessionStore.SessionHandle child = childSession.get();
        if (child == null || message == null) return;
        String type = switch (message.role()) {
            case "system" -> SessionEvent.Types.SYSTEM_MESSAGE;
            case "assistant" -> SessionEvent.Types.ASSISTANT_MESSAGE;
            case "tool" -> SessionEvent.Types.TOOL_RESULT;
            default -> SessionEvent.Types.USER_MESSAGE;
        };
        ObjectNode payload = JSON_MAPPER.createObjectNode().put("source", source);
        payload.set("message", JSON_MAPPER.valueToTree(message));
        if ("tool".equals(message.role())) {
            payload.put("invocationId", message.toolCallId() == null ? "unknown" : message.toolCallId());
        }
        try {
            child.append(new SessionEventDraft(type, "plan", child.manifest().actor(), source,
                    false, SessionEvent.SurfaceOperation.append(), payload));
        } catch (IOException e) {
            throw new IllegalStateException("unable to append plan child message", e);
        }
    }

    private static void printToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
            default -> toolName != null && toolName.startsWith("mcp__")
                    ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON_MAPPER.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                default -> null;
            };
            if (key == null) {
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }

    private static final class StreamState {
        private volatile boolean streamedOutput;

        private void markStreamed() {
            this.streamedOutput = true;
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }
    }

    private static final class TaskStreamRenderer implements LlmClient.StreamListener {
        private final String taskId;
        private final StreamState streamState;
        private final PrintStream out;
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        private TaskStreamRenderer(String taskId, StreamState streamState, PrintStream out) {
            this.taskId = taskId;
            this.streamState = streamState;
            this.out = out;
        }

        @Override
        public synchronized void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (contentStarted) {
                lateReasoning.append(delta);
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            } else {
                reasoningRenderer.append(delta);
            }
            out.flush();
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out.println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                    TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out.println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                // content 可能只是 tool-call 前的叙述，也可能是最终回答，用"输出"避免误导。
                out.println(AnsiStyle.section("🤖 任务输出 [" + taskId + "]"));
                contentRenderer = new TerminalMarkdownRenderer(out);
                contentStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            }
            contentRenderer.append(delta);
            out.flush();
        }

        private synchronized void finish() {
            if (streamedOutput) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                if (contentRenderer != null) {
                    contentRenderer.finish();
                }
                flushLateReasoning();
                out.println("\n");
            }
        }

        /**
         * 两次 iteration 之间（通常是一次 tool-call 分支完成后）调用：收尾当前渲染器并重置状态，
         * 让下一轮迭代能重新打印 🧠 / 🤖 标题，避免标题和内容被 HITL / 工具执行中断而错位。
         */
        private synchronized void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            flushLateReasoning();
            pendingReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                out.println();
            }
        }

        private synchronized boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void flushLateReasoning() {
            String late = lateReasoning.toString().trim();
            if (late.isEmpty()) {
                lateReasoning.setLength(0);
                return;
            }
            out.println();
            out.println(AnsiStyle.heading("🧠 补充思考 [" + taskId + "]"));
            TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(out);
            renderer.append(late);
            renderer.finish();
            lateReasoning.setLength(0);
        }
    }

    private StepBriefing buildStepBriefing(String goal, ExecutionPlan plan, Task task,
                                           List<TurnToolPolicy.TrustedUrlContext> dependencyUrls,
                                           String retryFeedback) {
        List<Task> completedDependencies = task.getDependencies().stream()
                .map(plan::getTask)
                .filter(Objects::nonNull)
                .filter(dependency -> dependency.getStatus() == Task.TaskStatus.COMPLETED)
                .toList();
        List<String> trustedUrls = dependencyUrls.stream()
                .flatMap(contextItem -> contextItem.urls().stream())
                .toList();
        return new StepBriefing(goal, task, completedDependencies, trustedUrls, retryFeedback);
    }

    private String buildFinalResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId()))) {
                continue;
            }
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }

        if (!result.isEmpty()) {
            return result.toString();
        }

        return plan.getAllTasks().stream()
                .filter(task -> !Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId())))
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

}
