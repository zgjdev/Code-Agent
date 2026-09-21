package com.codeagent.agent;

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
    void resumesPersistedPlanWithoutReplanningOrRepeatingCompletedTask() throws Exception {
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
        ExecutionPlan persisted = new ExecutionPlan("plan-resume", "继续原计划");
        Task completed = new Task("task_1", "已经完成", Task.TaskType.ANALYSIS);
        Task pending = new Task("task_2", "只执行剩余节点", Task.TaskType.ANALYSIS, List.of("task_1"));
        persisted.addTask(completed);
        persisted.addTask(pending);
        persisted.computeExecutionOrder();
        store.savePlan(tempDir, persisted);
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

        String result = agent.run("继续原计划");

        assertTrue(result.contains("计划执行完成"), result);
        assertEquals(0, planner.createCalls.get(), "恢复已有 DAG 时不应重新规划");
        assertEquals(1, client.snapshots.size(), "只应执行未完成节点");
        String prompt = client.snapshots.get(0).stream()
                .map(message -> message.content() == null ? "" : message.content())
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("task_2"), prompt);
        assertFalse(prompt.contains("当前任务：task_1"), prompt);
        assertTrue(store.findResumable(tempDir, "继续原计划").isEmpty(),
                "完成后的 Plan 不应继续作为恢复候选");
    }

    @Test
    void interruptedTaskReceivesRecoverySafetyBriefing() throws Exception {
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
        ExecutionPlan persisted = new ExecutionPlan("plan-interrupted", "恢复中断节点");
        Task interrupted = new Task("task_1", "修改已有产物", Task.TaskType.ANALYSIS);
        persisted.addTask(interrupted);
        persisted.computeExecutionOrder();
        store.savePlan(tempDir, persisted);
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

        agent.run("恢复中断节点");

        String prompt = client.snapshots.get(0).stream()
                .map(message -> message.content() == null ? "" : message.content())
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("进程中断恢复"), prompt);
        assertTrue(prompt.contains("不要假设上次副作用未发生"), prompt);
    }

    private static final class FailingIfCalledPlanner extends Planner {
        private final AtomicInteger createCalls = new AtomicInteger();

        private FailingIfCalledPlanner(LlmClient client) {
            super(client, new PrintStream(new ByteArrayOutputStream()));
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            createCalls.incrementAndGet();
            throw new AssertionError("恢复路径不应重新调用 Planner");
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
