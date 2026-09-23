package com.codeagent.agent;

import com.codeagent.history.ParentConversationContext;
import com.codeagent.history.SessionEvent;
import com.codeagent.history.SessionEventDraft;
import com.codeagent.history.SessionStore;
import com.codeagent.llm.GLMClient;
import com.codeagent.llm.LlmClient;
import com.codeagent.plan.ExecutionPlan;
import com.codeagent.plan.PlanStateStore;
import com.codeagent.plan.Planner;
import com.codeagent.plan.Task;
import com.codeagent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlanExecuteRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void resumesCurrentSessionPlanWithoutReplanningOrRepeatingCompletedTask() throws Exception {
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
            ExecutionPlan persisted = new ExecutionPlan("plan-resume", "原始复杂任务");
            Task completed = new Task("task_1", "已经完成", Task.TaskType.ANALYSIS);
            Task pending = new Task("task_2", "只执行剩余节点", Task.TaskType.ANALYSIS, List.of("task_1"));
            persisted.addTask(completed);
            persisted.addTask(pending);
            persisted.computeExecutionOrder();
            store.savePlan(tempDir, session.sessionId(), persisted);
            persisted.markStarted();
            store.checkpointPlan(persisted);
            completed.markCompleted("old-result");
            store.checkpointTask(persisted.getId(), completed);

            FailingIfCalledPlanner planner = new FailingIfCalledPlanner(client);
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    client,
                    registry,
                    planner,
                    null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream()),
                    PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(store);
            agent.setParentConversationContext(parentContext(session));

            String result = agent.resumeActivePlan();

            assertTrue(result.contains("计划执行完成"), result);
            assertEquals(0, planner.createCalls.get(), "恢复已有 DAG 时不应重新规划");
            assertEquals(1, client.snapshots.size(), "只应执行未完成节点");
            String prompt = joinedPrompt(client.snapshots.get(0));
            assertTrue(prompt.contains("task_2"), prompt);
            assertFalse(prompt.contains("当前任务：task_1"), prompt);
            assertTrue(prompt.contains("old-result"),
                    "恢复后的下游任务必须重新拿到已完成依赖的持久化 result: " + prompt);
            assertTrue(store.findActive(tempDir, session.sessionId()).isEmpty(),
                    "完成后的 Plan 不应继续作为 active Plan");
        }
    }

    @Test
    void interruptedTaskReceivesRecoverySafetyBriefing() throws Exception {
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
            ExecutionPlan persisted = new ExecutionPlan("plan-interrupted", "恢复中断节点");
            Task interrupted = new Task("task_1", "修改已有产物", Task.TaskType.ANALYSIS);
            persisted.addTask(interrupted);
            persisted.computeExecutionOrder();
            store.savePlan(tempDir, session.sessionId(), persisted);
            persisted.markStarted();
            store.checkpointPlan(persisted);
            interrupted.markStarted();
            store.checkpointTask(persisted.getId(), interrupted);

            PlanExecuteAgent agent = new PlanExecuteAgent(
                    client,
                    registry,
                    new FailingIfCalledPlanner(client),
                    null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream()),
                    PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(store);
            agent.setParentConversationContext(parentContext(session));

            agent.resumeActivePlan();

            String prompt = joinedPrompt(client.snapshots.get(0));
            assertTrue(prompt.contains("进程中断恢复"), prompt);
            assertTrue(prompt.contains("不要假设上次副作用未发生"), prompt);
        }
    }

    @Test
    void newPlanIsRejectedWhileSessionAlreadyHasActivePlan() throws Exception {
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
            store.savePlan(tempDir, session.sessionId(), singleTaskPlan("active-plan", "已有任务"));

            FailingIfCalledPlanner planner = new FailingIfCalledPlanner(client);
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    client,
                    registry,
                    planner,
                    null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream()),
                    PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(store);
            agent.setParentSession(session);

            String result = agent.run("一个全新的任务");

            assertTrue(result.contains("/plan resume"), result);
            assertTrue(result.contains("/plan abandon"), result);
            assertEquals(0, planner.createCalls.get(), "存在 active Plan 时不应创建第二个 Plan");
        }
    }

    @Test
    void abandonActivePlanAllowsNewPlanAfterwards() throws Exception {
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
            store.savePlan(tempDir, session.sessionId(), singleTaskPlan("active-plan", "已有任务"));

            PlanExecuteAgent agent = new PlanExecuteAgent(
                    client,
                    registry,
                    new FailingIfCalledPlanner(client),
                    null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream()),
                    PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(store);
            agent.setParentSession(session);

            String result = agent.abandonActivePlan();

            assertTrue(result.contains("已放弃"), result);
            assertTrue(store.findActive(tempDir, session.sessionId()).isEmpty());
        }
    }

    @Test
    void acceptedPlanPersistsOneTopLevelUserAndAssistantTurn() throws Exception {
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history-conversation"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            Agent reactAgent = new Agent(client, registry);
            reactAgent.attachSession(session);
            ParentConversationContext context = reactAgent.getParentConversationContext();
            PlanStateStore store = new PlanStateStore(tempDir.resolve("conversation-plans.db"));
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    client,
                    registry,
                    new SingleTaskPlanner(client),
                    reactAgent.getMemoryManager(),
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream()),
                    PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(store);
            agent.setParentConversationContext(context);

            String result = agent.run("完成当前分析", "完成当前分析");

            assertTrue(result.contains("计划执行完成"), result);
            assertEquals(List.of("user", "assistant"),
                    session.projection().conversationMessages().stream()
                            .map(LlmClient.Message::role).toList());
            assertEquals("完成当前分析",
                    session.projection().conversationMessages().get(0).content());
            assertTrue(session.projection().conversationMessages().get(1).content()
                    .contains("剩余任务完成"));
            assertTrue(reactAgent.getConversationHistory().stream()
                    .anyMatch(message -> "user".equals(message.role())
                            && "完成当前分析".equals(message.content())),
                    "Plan 顶层 user 必须立即进入共享 ReAct Provider Surface");
            assertTrue(reactAgent.getConversationHistory().stream()
                    .anyMatch(message -> "assistant".equals(message.role())
                            && message.content() != null
                            && message.content().contains("剩余任务完成")),
                    "Plan 顶层 result 必须立即进入共享 ReAct Provider Surface");
            assertTrue(session.projection().openTurns().isEmpty());
            assertTrue(store.findActive(tempDir, session.sessionId()).isEmpty());
        }
    }

    @Test
    void durableSessionRefusesToExecuteWhenPlanStoreIsUnavailable() throws Exception {
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history-no-store"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            ParentConversationContext context = parentContext(session);
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    client,
                    registry,
                    new SingleTaskPlanner(client),
                    null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream()),
                    PipelineOptions.PLAN_PRESET);
            agent.setParentConversationContext(context);

            String result = agent.run("不能执行", "不能执行");

            assertTrue(result.contains("Plan 持久化不可用"), result);
            assertEquals(0, client.snapshots.size(), "durable save gate 失败后不得开始 Task LLM 调用");
            assertTrue(session.projection().topLevelConversation().isEmpty(),
                    "未成功持久化的 Plan 不得创建 top-level conversation turn");
        }
    }

    @Test
    void configuredPlanStoreWithoutParentSessionRefusesExecution() throws Exception {
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        PlanExecuteAgent agent = new PlanExecuteAgent(
                client,
                registry,
                new SingleTaskPlanner(client),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()),
                PipelineOptions.PLAN_PRESET);
        agent.setPlanStateStore(new PlanStateStore(tempDir.resolve("no-session-plans.db")));

        String result = agent.run("不得在无 Session 时执行", "不得在无 Session 时执行");

        assertTrue(result.contains("持久化 Session"), result);
        assertEquals(0, client.snapshots.size(),
                "PlanStateStore 已启用时，缺 Parent Session 必须在任何 Task 调用前失败");
    }

    @Test
    void resumeStopsWhenParentConversationReconciliationFails() throws Exception {
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history-reconcile-failure"))) {
            SessionStore.SessionHandle stale =
                    sessions.create(new SessionStore.SessionCreateRequest(
                            tempDir, "glm", "test", null, "react", "agent"));
            String sessionId = stale.sessionId();
            ParentConversationContext staleContext = parentContext(stale);
            stale.close();

            try (SessionStore.SessionHandle resumed = sessions.resumeWritable(sessionId, tempDir)) {
                PlanStateStore store = new PlanStateStore(tempDir.resolve("reconcile-failure.db"));
                ExecutionPlan persisted = singleTaskPlan("plan-reconcile-failure", "恢复任务");
                store.savePlan(tempDir, sessionId, "恢复任务", persisted);

                PlanExecuteAgent agent = new PlanExecuteAgent(
                        client,
                        registry,
                        new FailingIfCalledPlanner(client),
                        null,
                        (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                        new PrintStream(new ByteArrayOutputStream()),
                        PipelineOptions.PLAN_PRESET);
                agent.setPlanStateStore(store);
                agent.setParentConversationContext(staleContext);
                agent.setParentSession(resumed);

                String result = agent.resumeActivePlan();

                assertTrue(result.contains("恢复计划失败"), result);
                assertEquals(0, client.snapshots.size(),
                        "Parent conversation reconciliation 失败后不得继续 Task 执行");
            }
        }
    }

    @Test
    void resumedExecutionReplanKeepsOriginalPriorConversation() throws Exception {
        FailThenSucceedClient client = new FailThenSucceedClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history-resume-replan"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            ParentConversationContext context = parentContext(session);
            appendReactSemantic(context, "user", "之前我们讨论的是认证模块缓存");
            appendReactSemantic(context, "assistant", "上一轮已经完成缓存层重构");

            PlanStateStore store = new PlanStateStore(tempDir.resolve("resume-replan.db"));
            ExecutionPlan persisted = singleTaskPlan("plan-resume-replan", "继续当前优化");
            store.savePlan(tempDir, session.sessionId(), "继续刚才那个缓存优化", persisted);

            CapturingReplanPlanner planner = new CapturingReplanPlanner(client);
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    client,
                    registry,
                    planner,
                    null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream()),
                    PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(store);
            agent.setParentConversationContext(context);

            String result = agent.resumeActivePlan();

            assertTrue(result.contains("计划执行完成"), result);
            assertNotNull(planner.priorConversationContext);
            assertTrue(planner.priorConversationContext.contains("认证模块缓存"),
                    planner.priorConversationContext);
            assertTrue(planner.priorConversationContext.contains("缓存层重构"),
                    planner.priorConversationContext);
            assertFalse(planner.priorConversationContext.contains("继续刚才那个缓存优化"),
                    "execution replan 使用的 prior snapshot 不应重复当前 Plan user turn");
        }
    }

    @Test
    void durableRecoveryCommandsRequireParentSession() throws Exception {
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        PlanExecuteAgent agent = new PlanExecuteAgent(
                client,
                registry,
                new FailingIfCalledPlanner(client),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()),
                PipelineOptions.PLAN_PRESET);
        agent.setPlanStateStore(new PlanStateStore(tempDir.resolve("plans.db")));

        assertTrue(agent.resumeActivePlan().contains("持久化 Session"));
        assertTrue(agent.abandonActivePlan().contains("持久化 Session"));
    }

    private void appendReactSemantic(ParentConversationContext context,
                                     String role,
                                     String content) throws Exception {
        LlmClient.Message message = "assistant".equals(role)
                ? LlmClient.Message.assistant(content)
                : LlmClient.Message.user(content);
        ObjectNode payload = JSON.createObjectNode();
        payload.set("message", JSON.valueToTree(message));
        payload.putObject("conversation")
                .put("mode", "react")
                .put("role", role)
                .put("content", content);
        context.append(new SessionEventDraft(
                "assistant".equals(role)
                        ? SessionEvent.Types.ASSISTANT_MESSAGE
                        : SessionEvent.Types.USER_MESSAGE,
                "react", "agent", "test", false,
                SessionEvent.SurfaceOperation.append(), payload));
        context.synchronizeProviderFromProjection();
    }

    private ParentConversationContext parentContext(SessionStore.SessionHandle session)
            throws Exception {
        ParentConversationContext context =
                new ParentConversationContext(LlmClient.Message.system("system"));
        context.setSessionHandle(session);
        ObjectNode payload = JSON.createObjectNode();
        payload.set("message", JSON.valueToTree(LlmClient.Message.system("system")));
        context.append(new SessionEventDraft(
                SessionEvent.Types.SYSTEM_MESSAGE,
                "react", "agent", "test", false,
                SessionEvent.SurfaceOperation.append(), payload));
        context.synchronizeProviderFromProjection();
        return context;
    }

    private static ExecutionPlan singleTaskPlan(String id, String goal) {
        ExecutionPlan plan = new ExecutionPlan(id, goal);
        plan.addTask(new Task("task_1", "分析", Task.TaskType.ANALYSIS));
        assertTrue(plan.computeExecutionOrder());
        return plan;
    }

    private static String joinedPrompt(List<LlmClient.Message> messages) {
        return messages.stream()
                .map(message -> message.content() == null ? "" : message.content())
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static final class SingleTaskPlanner extends Planner {
        private SingleTaskPlanner(LlmClient client) {
            super(client, new PrintStream(new ByteArrayOutputStream()));
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            return singleTaskPlan("plan-new", goal);
        }
    }

    private static final class CapturingReplanPlanner extends Planner {
        private String priorConversationContext;

        private CapturingReplanPlanner(LlmClient client) {
            super(client, new PrintStream(new ByteArrayOutputStream()));
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            throw new AssertionError("resume 路径不应重新 createPlan");
        }

        @Override
        public ExecutionPlan replan(ExecutionPlan failedPlan,
                                    String failureReason,
                                    String priorConversationContext) {
            this.priorConversationContext = priorConversationContext;
            return singleTaskPlan("plan-replanned", "重新规划后的任务");
        }
    }

    private static final class FailingIfCalledPlanner extends Planner {
        private final AtomicInteger createCalls = new AtomicInteger();

        private FailingIfCalledPlanner(LlmClient client) {
            super(client, new PrintStream(new ByteArrayOutputStream()));
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            createCalls.incrementAndGet();
            throw new AssertionError("该路径不应调用 Planner");
        }
    }

    private static final class FailThenSucceedClient extends GLMClient {
        private int calls;

        private FailThenSucceedClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            calls++;
            if (calls == 1) {
                throw new IOException("force execution replan");
            }
            return new ChatResponse("assistant", "replanned-success", null, 10, 2);
        }
    }

    private static final class RecordingClient extends GLMClient {
        private final List<List<Message>> snapshots = new ArrayList<>();

        private RecordingClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            snapshots.add(List.copyOf(messages));
            return new ChatResponse("assistant", "剩余任务完成", null, 10, 2);
        }
    }
}
