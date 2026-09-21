package com.codeagent.agent;

import com.codeagent.history.SessionStore;
import com.codeagent.llm.GLMClient;
import com.codeagent.llm.LlmClient;
import com.codeagent.plan.ExecutionPlan;
import com.codeagent.plan.PlanStateStore;
import com.codeagent.plan.Planner;
import com.codeagent.plan.Task;
import com.codeagent.tool.ToolRegistry;
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
            agent.setParentSession(session);

            String result = agent.resumeActivePlan();

            assertTrue(result.contains("计划执行完成"), result);
            assertEquals(0, planner.createCalls.get(), "恢复已有 DAG 时不应重新规划");
            assertEquals(1, client.snapshots.size(), "只应执行未完成节点");
            String prompt = joinedPrompt(client.snapshots.get(0));
            assertTrue(prompt.contains("task_2"), prompt);
            assertFalse(prompt.contains("当前任务：task_1"), prompt);
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
            agent.setParentSession(session);

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
