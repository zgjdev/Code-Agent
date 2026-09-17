package com.codeagent.agent;

import com.codeagent.llm.GLMClient;
import com.codeagent.llm.LlmClient;
import com.codeagent.memory.LongTermMemory;
import com.codeagent.memory.MemoryManager;
import com.codeagent.plan.ExecutionPlan;
import com.codeagent.plan.Planner;
import com.codeagent.plan.Task;
import com.codeagent.tool.ToolRegistry;
import com.codeagent.tool.ToolRegistry.ToolExecutionResult;
import com.codeagent.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldKeepPlanExecutionArtifactsInTheTaskConversationOnly() throws Exception {
        Path sampleFile = Files.createFile(tempDir.resolve("sample.txt"));
        Files.writeString(sampleFile, "plan-memory-content");

        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        List.of(new LlmClient.ToolCall(
                                "call_1",
                                new LlmClient.ToolCall.Function(
                                        "read_file",
                                        "{\"path\":\"" + sampleFile.toString().replace("\\", "\\\\") + "\"}"
                                )
                        )),
                        120,
                        30
                ),
                new LlmClient.ChatResponse("assistant", "已读取并确认文件内容", null, 140, 40)
        ));

        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                4096,
                128000,
                new LongTermMemory(tempDir.resolve("memory-store").toFile())
        );
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setProjectPath(tempDir.toString());
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                toolRegistry,
                new StubPlanner(llmClient),
                memoryManager,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("请读取测试文件并确认内容");

        assertTrue(result.contains("计划执行完成"));
        assertTrue(llmClient.messageSnapshots.stream().flatMap(List::stream)
                .anyMatch(message -> message.content() != null
                        && message.content().contains("请读取测试文件并确认内容")));
        assertTrue(llmClient.messageSnapshots.stream().flatMap(List::stream)
                .anyMatch(message -> message.content() != null
                        && message.content().contains("plan-memory-content")));
        assertEquals(0, memoryManager.getLongTermMemory().size());
    }

    @Test
    void shouldContinuePlanTaskBeyondLegacyFiveIterationLimit() throws Exception {
        String old = System.getProperty("codeagent.react.hard.max.iterations");
        try {
            System.clearProperty("codeagent.react.hard.max.iterations");
            List<LlmClient.ChatResponse> responses = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Path file = tempDir.resolve("sample-" + i + ".txt");
                Files.writeString(file, "content-" + i);
                responses.add(new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        List.of(new LlmClient.ToolCall(
                                "call_" + i,
                                new LlmClient.ToolCall.Function(
                                        "read_file",
                                        "{\"path\":\"" + file.toString().replace("\\", "\\\\") + "\"}"))),
                        10,
                        2));
            }
            responses.add(new LlmClient.ChatResponse(
                    "assistant", "六个文件均已读取完成", null, 10, 2));

            StubGLMClient llmClient = new StubGLMClient(responses);
            ToolRegistry toolRegistry = new ToolRegistry();
            toolRegistry.setProjectPath(tempDir.toString());
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    llmClient,
                    toolRegistry,
                    new StubPlanner(llmClient),
                    null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute());

            String result = agent.run("依次读取六个测试文件并汇总");

            assertTrue(result.contains("计划执行完成"));
            assertEquals(7, llmClient.toolSnapshots.size(), "计划任务不应再在第 5 轮提前退出");
        } finally {
            if (old == null) {
                System.clearProperty("codeagent.react.hard.max.iterations");
            } else {
                System.setProperty("codeagent.react.hard.max.iterations", old);
            }
        }
    }

    @Test
    void shouldNotExtractFactsWhenPlanIsCanceled() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of());
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("memory-store-cancel").toFile());
        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                4096,
                128000,
                longTermMemory
        );
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                memoryManager,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel()
        );

        String result = agent.run("列出当前目录的文件");

        assertEquals("⏹️ 已取消本次计划执行。", result);
        assertEquals(0, longTermMemory.size());
    }

    @Test
    void shouldNotRepeatStreamedTaskOutputInFinalPlanSummary() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.streamed(new LlmClient.ChatResponse(
                        "assistant",
                        "当前目录包含 8 个目录和 8 个文件。",
                        null,
                        60,
                        20
                ))
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("列出当前目录的文件");

        assertEquals("✅ 计划执行完成！", result);
    }

    @Test
    void shouldNotPrintEmptyTaskReasoningHeadingAndShouldUseOutputLabel() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.scripted(
                        listener -> {
                            listener.onReasoningDelta("  \n");
                            listener.onContentDelta("我来读取 pom.xml 文件。");
                        },
                        new LlmClient.ChatResponse(
                                "assistant",
                                "我来读取 pom.xml 文件。",
                                "  \n",
                                null,
                                60,
                                20
                        )
                )
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            agent.run("读取 pom.xml");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("任务思考 [task_1]"),
                "空白 reasoning 不应打印空的任务思考标题: " + rendered);
        assertTrue(rendered.contains("任务输出 [task_1]"));
        assertFalse(rendered.contains("任务结果 [task_1]"),
                "tool-call 前后的流式 content 不应被误标成任务结果: " + rendered);
    }

    @Test
    void supplementRebuildsToolPolicyBeforeReplanning() throws Exception {
        LlmClient.ToolCall search = new LlmClient.ToolCall(
                "call_search",
                new LlmClient.ToolCall.Function(
                        "web_search",
                        "{\"query\":\"最新 Agent 资料\"}"
                )
        );
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(search), 10, 2),
                new LlmClient.ChatResponse("assistant", "已按搜索结果改写", null, 20, 5)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        AtomicInteger reviews = new AtomicInteger();
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                registry,
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> reviews.getAndIncrement() == 0
                        ? PlanExecuteAgent.PlanReviewDecision.supplement("请联网搜索最新资料")
                        : PlanExecuteAgent.PlanReviewDecision.execute()
        );

        agent.run("改写这个标题");

        assertEquals(1, registry.invocations.size(),
                "首轮 tools=" + llmClient.toolSnapshots.get(0).stream().map(tool -> tool.name()).toList());
        assertEquals("web_search", registry.invocations.get(0).name());
        assertTrue(llmClient.toolSnapshots.get(0).stream()
                .anyMatch(tool -> "web_search".equals(tool.name())));
    }

    @Test
    void noWebSupplementTightensToolPolicyBeforeReplanning() throws Exception {
        LlmClient.ToolCall search = new LlmClient.ToolCall(
                "call_search",
                new LlmClient.ToolCall.Function("web_search", "{\"query\":\"最新资料\"}"));
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(search), 10, 2),
                new LlmClient.ChatResponse("assistant", "已仅按已有文本改写", null, 20, 5)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        AtomicInteger reviews = new AtomicInteger();
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                registry,
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> reviews.getAndIncrement() == 0
                        ? PlanExecuteAgent.PlanReviewDecision.supplement("不需要联网，只改写已有标题")
                        : PlanExecuteAgent.PlanReviewDecision.execute()
        );

        agent.run("请联网搜索资料后改写这个标题");

        assertTrue(registry.invocations.isEmpty());
        assertTrue(llmClient.toolSnapshots.get(0).stream()
                .noneMatch(tool -> "web_search".equals(tool.name())));
    }

    @Test
    void dependentTaskInheritsOnlyTypedSearchUrlProvenance() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(new LlmClient.ToolCall(
                        "search",
                        new LlmClient.ToolCall.Function("web_search", "{\"query\":\"目标文章\"}"))), 10, 2),
                new LlmClient.ChatResponse("assistant", "已定位目标文章", null, 10, 2),
                new LlmClient.ChatResponse("assistant", "", List.of(new LlmClient.ToolCall(
                        "fetch",
                        new LlmClient.ToolCall.Function(
                                "web_fetch", "{\"url\":\"https://example.com/article\"}"))), 10, 2),
                new LlmClient.ChatResponse("assistant", "已抓取正文", null, 10, 2)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                registry,
                new TwoTaskWebPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("帮我搜索目标文章并抓取正文");

        assertTrue(result.contains("计划执行完成"));
        assertEquals(List.of("web_search", "web_fetch"),
                registry.invocations.stream().map(ToolInvocation::name).toList());
        assertTrue(llmClient.toolSnapshots.get(2).stream()
                .anyMatch(tool -> "web_fetch".equals(tool.name())),
                "依赖任务首轮应继承前置 web_search 的类型化 URL 授权");
    }

    @Test
    void stepReviewRetriesUntilReviewerApproves() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "第一版结果", null, 10, 5),
                new LlmClient.ChatResponse("assistant",
                        "{\"approved\": false, \"issues\": [\"缺少边界处理\"]}", null, 10, 5),
                new LlmClient.ChatResponse("assistant", "第二版结果", null, 10, 5),
                new LlmClient.ChatResponse("assistant", "{\"approved\": true, \"issues\": []}", null, 10, 5)
        ));
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                new MemoryManager(llmClient),
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()),
                PipelineOptions.TEAM_PRESET);

        String result = agent.run("读取测试文件");

        assertTrue(result.contains("计划执行完成"), result);
        assertTrue(llmClient.messageSnapshots.stream()
                        .flatMap(List::stream)
                        .anyMatch(message -> message.content() != null
                                && message.content().contains("缺少边界处理")),
                "重试时必须把审查反馈注入下一次执行的下行简报");
    }

    @Test
    void stepReviewDisabledKeepsSingleAttemptPerTask() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "唯一一次结果", null, 10, 5)
        ));
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                new MemoryManager(llmClient),
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()),
                PipelineOptions.PLAN_PRESET);

        agent.run("读取测试文件");

        long executionAttempts = llmClient.messageSnapshots.stream()
                .flatMap(List::stream)
                .filter(message -> message.content() != null
                        && message.content().contains("当前任务：task_1"))
                .count();
        assertEquals(1, executionAttempts, "stepReview 关闭时每个任务只应执行一次");
    }

    @Test
    void fallsBackToExistingOutcomeAfterRetriesExhausted() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "第一版结果", null, 10, 5),
                new LlmClient.ChatResponse("assistant",
                        "{\"approved\": false, \"issues\": [\"仍不合格\"]}", null, 10, 5),
                new LlmClient.ChatResponse("assistant", "第二版结果", null, 10, 5),
                new LlmClient.ChatResponse("assistant",
                        "{\"approved\": false, \"issues\": [\"仍不合格\"]}", null, 10, 5),
                new LlmClient.ChatResponse("assistant", "第三版结果", null, 10, 5),
                new LlmClient.ChatResponse("assistant",
                        "{\"approved\": false, \"issues\": [\"仍不合格\"]}", null, 10, 5)
        ));
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                new MemoryManager(llmClient),
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()),
                PipelineOptions.TEAM_PRESET);

        String result = agent.run("读取测试文件");

        assertTrue(result.contains("计划执行完成") || result.contains("计划部分完成"),
                "重试耗尽后必须回到既有结果汇总路径，不得抛异常：" + result);
    }

    @Test
    void runsIndependentTasksInParallel() throws Exception {
        CountDownLatch tasksInFlight = new CountDownLatch(2);
        AtomicInteger currentConcurrency = new AtomicInteger();
        AtomicInteger peakConcurrency = new AtomicInteger();
        BarrierStubGLMClient llmClient =
                new BarrierStubGLMClient(tasksInFlight, currentConcurrency, peakConcurrency);
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new TwoIndependentTaskPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()),
                PipelineOptions.PLAN_PRESET
        );

        String result = agent.run("并行完成两件互不依赖的事");

        assertTrue(result.contains("计划执行完成"), result);
        assertEquals(2, peakConcurrency.get(), "同一批次的两个独立任务应并发进入 LLM 调用");
    }

    /**
     * 并行批次里的每个任务必须拿到属于自己的 Reviewer 会话。
     * 共用同一个 SubAgent 会让两条审查线程写同一份 ArrayList 历史（数据竞争）。
     */
    @Test
    void parallelStepReviewDoesNotShareReviewerHistory() throws Exception {
        CountDownLatch reviewsInFlight = new CountDownLatch(2);
        ParallelReviewGLMClient llmClient = new ParallelReviewGLMClient(reviewsInFlight);
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new TwoIndependentTaskPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()),
                PipelineOptions.FULL_PRESET
        );

        String result = agent.run("并行完成两件互不依赖的事");

        assertTrue(result.contains("计划执行完成"), result);
        assertEquals(2, llmClient.reviewMessageLists.size(), "两个并行任务应各触发一次审查");
        assertNotSame(llmClient.reviewMessageLists.get(0), llmClient.reviewMessageLists.get(1),
                "并行任务的 Reviewer 不得共享同一份会话历史");
    }

    @Test
    void reportsIncompleteRunWhenFailureBlocksRemainingTasks() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "第一步完成", null, 10, 2),
                new LlmClient.ChatResponse("assistant", "第二步完成", null, 10, 2)
        ));
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new FailureBlockingPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()),
                PipelineOptions.PLAN_PRESET
        );

        String result = agent.run("按顺序执行四个步骤");

        assertTrue(result.contains("计划部分完成，有任务失败"), result);
        assertTrue(result.contains("task_3"), result);
        assertFalse(llmClient.messageSnapshots.stream().flatMap(List::stream)
                        .anyMatch(message -> message.content() != null
                                && message.content().contains("当前任务：task_4")),
                "前置任务失败后，被阻塞的任务不应执行");
    }

    /**
     * 早期失败会触发重规划，但重规划必须有次数上限：每轮重规划都会真实调用 LLM，
     * 失败原因不变时会一轮套一轮地递归下去，直到栈溢出。
     */
    @Test
    void capsReplanningWhenEarlyFailureKeepsRecurring() throws Exception {
        AlwaysFailingGLMClient llmClient = new AlwaysFailingGLMClient();
        ReplanningFailingPlanner planner = new ReplanningFailingPlanner(llmClient);
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                planner,
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()),
                PipelineOptions.PLAN_PRESET
        );

        String result = agent.run("注定失败的任务");

        assertEquals(1, planner.replanCalls.get(), "重规划必须封顶，不得无限递归");
        assertTrue(result.contains("task_1"), "重规划不得丢掉外层已发生的失败: " + result);
        assertTrue(result.contains("task_2"), "重规划后的计划结果应进入最终汇总: " + result);
    }

    private record StubResponse(LlmClient.ChatResponse response, boolean streamContent,
                                java.util.function.Consumer<LlmClient.StreamListener> streamScript) {
        private static StubResponse plain(LlmClient.ChatResponse response) {
            return new StubResponse(response, false, null);
        }

        private static StubResponse streamed(LlmClient.ChatResponse response) {
            return new StubResponse(response, true, null);
        }

        private static StubResponse scripted(java.util.function.Consumer<LlmClient.StreamListener> streamScript,
                                             LlmClient.ChatResponse response) {
            return new StubResponse(response, false, streamScript);
        }
    }

    private static final class StubPlanner extends Planner {
        private StubPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-test", goal);
            plan.addTask(new Task("task_1", "读取测试文件", Task.TaskType.FILE_READ));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static final class TwoTaskWebPlanner extends Planner {
        private TwoTaskWebPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-web", goal);
            plan.addTask(new Task("search", "搜索目标文章", Task.TaskType.ANALYSIS));
            plan.addTask(new Task("fetch", "抓取目标文章正文", Task.TaskType.FILE_READ, List.of("search")));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static final class TwoIndependentTaskPlanner extends Planner {
        private TwoIndependentTaskPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-parallel", goal);
            plan.addTask(new Task("task_1", "任务A", Task.TaskType.ANALYSIS));
            plan.addTask(new Task("task_2", "任务B", Task.TaskType.ANALYSIS));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static final class FailureBlockingPlanner extends Planner {
        private FailureBlockingPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-blocked", goal);
            plan.addTask(new Task("task_1", "第一步", Task.TaskType.ANALYSIS));
            plan.addTask(new Task("task_2", "第二步", Task.TaskType.ANALYSIS));
            plan.addTask(new Task("task_3", "第三步（LLM 调用失败）", Task.TaskType.ANALYSIS, List.of("task_2")));
            plan.addTask(new Task("task_4", "第四步（被前置失败阻塞）", Task.TaskType.ANALYSIS, List.of("task_3")));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    /** 两个独立任务必须在同一时刻都停留在 chat() 内，否则抛错。 */
    private static final class BarrierStubGLMClient extends GLMClient {
        private final CountDownLatch tasksInFlight;
        private final AtomicInteger currentConcurrency;
        private final AtomicInteger peakConcurrency;

        private BarrierStubGLMClient(CountDownLatch tasksInFlight,
                                     AtomicInteger currentConcurrency,
                                     AtomicInteger peakConcurrency) {
            super("test-key");
            this.tasksInFlight = tasksInFlight;
            this.currentConcurrency = currentConcurrency;
            this.peakConcurrency = peakConcurrency;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            int now = currentConcurrency.incrementAndGet();
            peakConcurrency.updateAndGet(previous -> Math.max(previous, now));
            tasksInFlight.countDown();
            try {
                if (!tasksInFlight.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("两个独立任务未并发进入 LLM 调用");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("并发探测被中断", e);
            } finally {
                currentConcurrency.decrementAndGet();
            }
            return new ChatResponse("assistant", "任务完成", null, 10, 2);
        }
    }

    /** 每次规划产出一个只含单个任务的计划，任务 id 带序号以便区分外层与重规划后的结果。 */
    private static final class ReplanningFailingPlanner extends Planner {
        private final AtomicInteger planCalls = new AtomicInteger();
        private final AtomicInteger replanCalls = new AtomicInteger();

        private ReplanningFailingPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            int index = planCalls.incrementAndGet();
            ExecutionPlan plan = new ExecutionPlan("plan-" + index, goal);
            plan.addTask(new Task("task_" + index, "注定失败的任务 " + index, Task.TaskType.ANALYSIS));
            plan.computeExecutionOrder();
            return plan;
        }

        @Override
        public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) {
            replanCalls.incrementAndGet();
            return createPlan(failedPlan.getGoal() + " / 重规划");
        }
    }

    private static final class AlwaysFailingGLMClient extends GLMClient {
        private AlwaysFailingGLMClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            throw new IOException("LLM 不可用");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            throw new IOException("LLM 不可用");
        }
    }

    /** 按提示词区分执行调用与审查调用：两个审查调用必须同时停留在 chat() 内。 */
    private static final class ParallelReviewGLMClient extends GLMClient {
        private final CountDownLatch reviewsInFlight;
        private final List<List<Message>> reviewMessageLists =
                Collections.synchronizedList(new ArrayList<>());

        private ParallelReviewGLMClient(CountDownLatch reviewsInFlight) {
            super("test-key");
            this.reviewsInFlight = reviewsInFlight;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            boolean reviewCall = messages.stream()
                    .anyMatch(message -> message.content() != null
                            && message.content().contains("质量检查专家"));
            if (!reviewCall) {
                return new ChatResponse("assistant", "任务完成", null, 10, 2);
            }
            reviewMessageLists.add(messages);
            reviewsInFlight.countDown();
            try {
                if (!reviewsInFlight.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("两个并行任务的审查调用未同时进入 LLM");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("审查并发探测被中断", e);
            }
            return new ChatResponse("assistant", "{\"approved\": true, \"issues\": []}", null, 10, 2);
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<StubResponse> responses;
        private final List<List<Tool>> toolSnapshots = new ArrayList<>();
        private final List<List<Message>> messageSnapshots = new ArrayList<>();

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses.stream().map(StubResponse::plain).toList());
        }

        private StubGLMClient(Queue<StubResponse> responses) {
            super("test-key");
            this.responses = responses;
        }

        private static StubGLMClient streaming(List<StubResponse> responses) {
            return new StubGLMClient(new ArrayDeque<>(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        // 并行批次里同一实例会被多个 plan-executor 线程同时调用，
        // 响应队列和快照列表都必须串行化，否则会读到并发写坏的 ArrayList。
        @Override
        public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools,
                                              StreamListener listener) throws IOException {
            toolSnapshots.add(tools == null ? List.of() : List.copyOf(tools));
            messageSnapshots.add(List.copyOf(messages));
            StubResponse stubResponse = responses.poll();
            if (stubResponse == null) {
                throw new IOException("缺少预设响应");
            }
            if (stubResponse.streamScript() != null) {
                stubResponse.streamScript().accept(listener);
            } else if (stubResponse.streamContent() && stubResponse.response().content() != null) {
                listener.onContentDelta(stubResponse.response().content());
            }
            return stubResponse.response();
        }
    }

    private static final class RecordingToolRegistry extends ToolRegistry {
        private final List<ToolInvocation> invocations = new ArrayList<>();

        @Override
        public List<ToolExecutionResult> executeTools(List<ToolInvocation> calls) {
            invocations.addAll(calls);
            return calls.stream()
                    .map(call -> new ToolExecutionResult(
                            call.id(),
                            call.name(),
                            call.argumentsJson(),
                            "1. 搜索结果 https://example.com/article",
                            0,
                            false,
                            List.of(),
                            true,
                            "web_search".equals(call.name())
                                    ? List.of("https://example.com/article") : List.of()))
                    .toList();
        }
    }
}
