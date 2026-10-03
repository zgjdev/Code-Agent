package com.codeagent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeagent.history.ConversationLedger;
import com.codeagent.llm.LlmClient;
import com.codeagent.llm.LlmTraceLogger;
import com.codeagent.llm.StructuredJsonExecutor;
import com.codeagent.llm.StructuredOutputSpec;
import com.codeagent.memory.TokenBudget;
import com.codeagent.prompt.PromptAssembler;
import com.codeagent.prompt.PromptContext;
import com.codeagent.prompt.PromptMode;
import com.codeagent.prompt.ProjectMemoryLoader;
import com.codeagent.util.AnsiStyle;
import com.codeagent.util.TerminalMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * 规划器 - 使用LLM将复杂任务分解为执行计划
 */
public class Planner {
    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StructuredOutputSpec PLAN_OUTPUT =
            new StructuredOutputSpec("execution_plan", buildPlanSchema(), false);

    private final LlmClient llmClient;
    private final PrintStream out;
    private final StructuredJsonExecutor structuredJsonExecutor = new StructuredJsonExecutor();
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private ConversationLedger conversationLedger = ConversationLedger.disabled();
    private Supplier<String> projectMemorySupplier = () ->
            ProjectMemoryLoader.createDefault(Path.of(".").toAbsolutePath().normalize()).loadForPrompt();

    public Planner(LlmClient llmClient) {
        this(llmClient, System.out);
    }

    public Planner(LlmClient llmClient, PrintStream out) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
    }

    public void setProjectMemorySupplier(Supplier<String> projectMemorySupplier) {
        this.projectMemorySupplier = projectMemorySupplier == null ? () -> "" : projectMemorySupplier;
    }

    public void setConversationLedger(ConversationLedger conversationLedger) {
        this.conversationLedger = conversationLedger == null
                ? ConversationLedger.disabled()
                : conversationLedger;
    }

    public record PlannerRequest(String goal, String priorConversationContext) {
        public PlannerRequest {
            goal = goal == null ? "" : goal;
            priorConversationContext = priorConversationContext == null ? "" : priorConversationContext.trim();
        }
    }

    /**
     * 为复杂任务创建执行计划。保留旧虚方法作为扩展点。
     */
    public ExecutionPlan createPlan(String goal) throws IOException {
        return createPlanInternal(new PlannerRequest(goal, ""));
    }

    public ExecutionPlan createPlan(PlannerRequest request) throws IOException {
        if (request.priorConversationContext().isBlank()) {
            // Preserve polymorphic behavior for existing Planner subclasses.
            return createPlan(request.goal());
        }
        return createPlanInternal(request);
    }

    private ExecutionPlan createPlanInternal(PlannerRequest request) throws IOException {
        String goal = request.goal();
        String priorConversationContext = request.priorConversationContext();
        out.println("📋 正在规划任务: " + goal + "\n");

        if (priorConversationContext.isBlank() && isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }

        List<LlmClient.Message> messages = buildPlanningMessages(request);
        conversationLedger.appendMessage("plan", "planner", "system_prompt", messages.get(0));
        conversationLedger.appendMessage("plan", "planner", "planning_request", messages.get(1));

        PlanningStreamRenderer streamRenderer = new PlanningStreamRenderer(out);
        StructuredJsonExecutor.Result<ExecutionPlan> structured = structuredJsonExecutor.execute(
                llmClient,
                messages,
                null,
                PLAN_OUTPUT,
                true,
                root -> parsePlan(goal, root),
                streamRenderer);
        LlmClient.ChatResponse response = structured.response();
        conversationLedger.appendMessage(
                "plan",
                "planner",
                "llm_response",
                LlmClient.Message.assistant(response.reasoningContent(), response.content()));
        LlmTraceLogger.logReasoning(log, "planner", llmClient, response.reasoningContent());
        streamRenderer.finish();
        return structured.value();
    }

    public int estimateRequestTokens(PlannerRequest request) {
        return TokenBudget.estimateMessagesTokens(buildPlanningMessages(request));
    }

    private List<LlmClient.Message> buildPlanningMessages(PlannerRequest request) {
        StringBuilder planningRequest = new StringBuilder();
        if (!request.priorConversationContext().isBlank()) {
            planningRequest.append(request.priorConversationContext()).append("\n\n");
        }
        planningRequest.append("[当前任务]\n")
                .append(request.goal())
                .append("\n\n请为当前任务制定执行计划。");
        return Arrays.asList(
                LlmClient.Message.system(promptAssembler.assemble(PromptMode.PLANNER, PromptContext.builder()
                        .projectMemoryContext(buildProjectMemoryContext())
                        .build())),
                LlmClient.Message.user(planningRequest.toString())
        );
    }

    private String buildProjectMemoryContext() {
        try {
            String context = projectMemorySupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to load CODEAGENT.md project memory for planner", e);
            return "";
        }
    }

    /**
     * 解析并校验 LLM 生成的计划 JSON。任何确定性业务校验失败都会交给
     * StructuredJsonExecutor 做一次 bounded repair。
     */
    private ExecutionPlan parsePlan(String goal, JsonNode root) throws IOException {
        validatePlanSchema(root, PLAN_OUTPUT.schema(), "plan");
        if (root == null || !root.isObject()) {
            throw new IOException("Planner response must be a JSON object");
        }
        JsonNode summaryNode = root.get("summary");
        JsonNode tasksNode = root.get("tasks");
        if (summaryNode == null || !summaryNode.isTextual()) {
            throw new IOException("Planner response missing textual summary");
        }
        if (tasksNode == null || !tasksNode.isArray() || tasksNode.isEmpty()) {
            throw new IOException("Planner response tasks must be a non-empty array");
        }

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summaryNode.asText());

        // 第一遍：创建所有任务（不处理依赖，因为可能有前向引用）
        Map<String, String> idMapping = new HashMap<>();
        int taskIndex = 1;

        for (JsonNode taskNode : tasksNode) {
            if (!taskNode.isObject()) {
                throw new IOException("Planner task must be a JSON object");
            }
            String originalId = taskNode.path("id").asText("").trim();
            String description = taskNode.path("description").asText("").trim();
            if (originalId.isEmpty()) {
                throw new IOException("Planner task id must be non-empty");
            }
            if (idMapping.containsKey(originalId)) {
                throw new IOException("Duplicate planner task id: " + originalId);
            }
            if (description.isEmpty()) {
                throw new IOException("Planner task description must be non-empty: " + originalId);
            }
            JsonNode typeNode = taskNode.get("type");

            String newId = "task_" + taskIndex++;
            idMapping.put(originalId, newId);

            String typeStr = typeNode == null ? null : typeNode.asText();
            Task.TaskType type = parseTaskType(typeStr, originalId);

            try {
                TaskResourceClaims resourceClaims = TaskResourceClaims.normalize(
                        Path.of(".").toAbsolutePath().normalize(), type, taskNode.get("resources"));
                List<String> acceptanceCriteria = parseTextArray(taskNode.path("acceptanceCriteria"));
                Set<EvidenceType> requiredEvidence = parseEvidence(taskNode.path("requiredEvidence"));
                plan.addTask(new Task(newId, description, type, List.of(), resourceClaims,
                        acceptanceCriteria, requiredEvidence));
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid resource declaration for task " + originalId, e);
            }
        }

        // 第二遍：建立依赖和被依赖关系
        taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = "task_" + taskIndex++;
            Task task = plan.getTask(newId);

            JsonNode depsNode = taskNode.path("dependencies");
            if (depsNode.isArray()) {
                for (JsonNode depNode : depsNode) {
                    if (!depNode.isTextual()) {
                        throw new IOException("Planner dependency id must be textual for " + newId);
                    }
                    String originalDepId = depNode.asText();
                    String newDepId = idMapping.get(originalDepId);
                    if (newDepId == null) {
                        throw new IOException("Unknown planner dependency '" + originalDepId + "' for " + newId);
                    }
                    Task dep = plan.getTask(newDepId);
                    task.addDependency(newDepId);
                    dep.addDependent(task.getId());
                }
            }
        }

        // 计算执行顺序
        if (!plan.computeExecutionOrder()) {
            throw new IOException("计划中存在循环依赖");
        }

        return plan;
    }

    private static void validatePlanSchema(JsonNode node, JsonNode schema, String fieldPath) throws IOException {
        String expectedType = schema.path("type").asText();
        boolean validType = node != null && switch (expectedType) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "boolean" -> node.isBoolean();
            default -> false;
        };
        if (!validType) {
            throw new IOException("Planner " + fieldPath + " must be of type " + expectedType);
        }

        JsonNode allowedValues = schema.get("enum");
        if (allowedValues != null) {
            boolean allowed = false;
            for (JsonNode allowedValue : allowedValues) {
                if (allowedValue.equals(node)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new IOException("Planner " + fieldPath + " must match a schema enum value");
            }
        }

        if (node.isObject()) {
            for (JsonNode requiredField : schema.path("required")) {
                if (!node.has(requiredField.asText())) {
                    throw new IOException("Planner " + fieldPath + " missing required field " + requiredField.asText());
                }
            }
            JsonNode properties = schema.path("properties");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode fieldSchema = properties.get(field.getKey());
                if (fieldSchema == null) {
                    if (!schema.path("additionalProperties").asBoolean(true)) {
                        throw new IOException("Planner " + fieldPath + " has unknown property " + field.getKey());
                    }
                } else {
                    validatePlanSchema(field.getValue(), fieldSchema, fieldPath + "." + field.getKey());
                }
            }
        } else if (node.isArray()) {
            for (int itemIndex = 0; itemIndex < node.size(); itemIndex++) {
                validatePlanSchema(node.get(itemIndex), schema.path("items"), fieldPath + "[" + itemIndex + "]");
            }
        }
    }

    private static JsonNode buildPlanSchema() {
        var root = MAPPER.createObjectNode();
        root.put("type", "object");
        var properties = root.putObject("properties");
        properties.putObject("summary").put("type", "string");

        var tasks = properties.putObject("tasks");
        tasks.put("type", "array");
        var task = tasks.putObject("items");
        task.put("type", "object");
        var taskProperties = task.putObject("properties");
        taskProperties.putObject("id").put("type", "string");
        taskProperties.putObject("description").put("type", "string");
        taskProperties.putObject("type").put("type", "string")
                .putArray("enum")
                .add("FILE_READ").add("FILE_WRITE").add("COMMAND")
                .add("ANALYSIS").add("VERIFICATION");
        taskProperties.putObject("dependencies")
                .put("type", "array")
                .putObject("items").put("type", "string");

        var resources = taskProperties.putObject("resources");
        resources.put("type", "object");
        var resourceProperties = resources.putObject("properties");
        resourceProperties.putObject("readPaths")
                .put("type", "array")
                .putObject("items").put("type", "string");
        resourceProperties.putObject("writePaths")
                .put("type", "array")
                .putObject("items").put("type", "string");
        resourceProperties.putObject("workspaceWrite").put("type", "boolean");
        resources.put("additionalProperties", false);

        taskProperties.putObject("acceptanceCriteria")
                .put("type", "array")
                .putObject("items").put("type", "string");
        taskProperties.putObject("requiredEvidence")
                .put("type", "array")
                .putObject("items")
                .put("type", "string")
                .putArray("enum")
                .add("DIFF").add("BUILD").add("TEST").add("LSP").add("TOOL_RESULT");
        task.putArray("required").add("id").add("description");
        task.put("additionalProperties", false);

        root.putArray("required").add("summary").add("tasks");
        root.put("additionalProperties", false);
        return root;
    }

    /**
     * 解析任务类型
     */
    private Task.TaskType parseTaskType(String typeStr, String taskId) throws IOException {
        if (typeStr == null) {
            return Task.TaskType.ANALYSIS;
        }
        return switch (typeStr) {
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "ANALYSIS" -> Task.TaskType.ANALYSIS;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            default -> throw new IOException("Unknown planner task type '" + typeStr + "' for " + taskId);
        };
    }

    private List<String> parseTextArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return List.copyOf(values);
    }

    private Set<EvidenceType> parseEvidence(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Set.of();
        }
        Set<EvidenceType> evidence = new LinkedHashSet<>();
        for (JsonNode item : node) {
            evidence.add(EvidenceType.valueOf(item.asText()));
        }
        return Collections.unmodifiableSet(evidence);
    }

    /**
     * 生成计划ID
     */
    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    /**
     * 根据执行结果重新规划。保留旧虚方法作为扩展点。
     */
    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        String goal = buildReplanGoal(failedPlan, failureReason);
        out.println("🔄 重新规划，原因: " + failureReason + "\n");
        return createPlan(goal);
    }

    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason,
                                String priorConversationContext) throws IOException {
        if (priorConversationContext == null || priorConversationContext.isBlank()) {
            // Preserve polymorphic behavior for existing Planner subclasses.
            return replan(failedPlan, failureReason);
        }
        out.println("🔄 重新规划，原因: " + failureReason + "\n");
        return createPlan(new PlannerRequest(
                buildReplanGoal(failedPlan, failureReason),
                priorConversationContext));
    }

    private String buildReplanGoal(ExecutionPlan failedPlan, String failureReason) {
        StringBuilder context = new StringBuilder();
        context.append("原任务: ").append(failedPlan.getGoal()).append("\n");
        context.append("失败原因: ").append(failureReason).append("\n");
        context.append("已完成的任务:\n");

        for (Task task : failedPlan.getAllTasks()) {
            if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                context.append("- ").append(task.getId())
                        .append(": ").append(task.getDescription())
                        .append("\n");
            }
        }
        context.append("\n请制定新的执行计划，避开之前的问题。");
        return context.toString();
    }

    private boolean isSimpleGoal(String goal) {
        if (goal == null) {
            return false;
        }

        String normalized = goal.trim();
        if (normalized.isEmpty()) {
            return false;
        }

        boolean hasMultiStepCue = normalized.contains("然后")
                || normalized.contains("并且")
                || normalized.contains("并")
                || normalized.contains("再")
                || normalized.contains("最后")
                || normalized.contains("同时")
                || normalized.contains("先")
                || normalized.contains("之后")
                || normalized.contains("接着")
                || normalized.contains("以及");
        if (hasMultiStepCue) {
            return false;
        }

        if (normalized.length() > 30) {
            return false;
        }

        return normalized.contains("列出")
                || normalized.contains("查看")
                || normalized.contains("读取")
                || normalized.contains("显示")
                || normalized.contains("执行")
                || normalized.contains("运行")
                || normalized.contains("搜索")
                || normalized.contains("当前目录")
                || normalized.contains("文件");
    }

    private ExecutionPlan createMinimalPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(buildMinimalSummary(goal));
        plan.addTask(new Task("task_1", goal.trim(), inferSimpleTaskType(goal)));
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("简单计划不应出现循环依赖");
        }
        return plan;
    }

    private String buildMinimalSummary(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.isEmpty()) {
            return "执行简单任务";
        }
        return "直接执行简单任务：" + normalized;
    }

    private Task.TaskType inferSimpleTaskType(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.contains("读取") || normalized.contains("打开") || normalized.contains("查看")
                && normalized.contains("文件")) {
            return Task.TaskType.FILE_READ;
        }
        if (normalized.contains("写入") || normalized.contains("修改") || normalized.contains("创建文件")) {
            return Task.TaskType.FILE_WRITE;
        }
        if (normalized.contains("分析") || normalized.contains("总结") || normalized.contains("解释")) {
            return Task.TaskType.ANALYSIS;
        }
        if (normalized.contains("验证") || normalized.contains("检查")) {
            return Task.TaskType.VERIFICATION;
        }
        return Task.TaskType.COMMAND;
    }

    private static final class PlanningStreamRenderer implements LlmClient.StreamListener {
        private final PrintStream out;
        private TerminalMarkdownRenderer reasoningRenderer;
        private boolean reasoningStarted;
        private boolean streamed;

        private PlanningStreamRenderer(PrintStream out) {
            this.out = out == null ? System.out : out;
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!reasoningStarted) {
                out.println(AnsiStyle.heading("🧠 规划思考"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningStarted = true;
                streamed = true;
            }
            reasoningRenderer.append(delta);
            out.flush();
        }

        private void finish() {
            if (streamed) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                out.println("\n");
            }
        }
    }
}
