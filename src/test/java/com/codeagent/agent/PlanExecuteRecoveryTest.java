package com.codeagent.agent;

import com.codeagent.history.ParentConversationContext;
import com.codeagent.history.SessionEvent;
import com.codeagent.history.SessionEventDraft;
import com.codeagent.history.SessionStore;
import com.codeagent.llm.GLMClient;
import com.codeagent.llm.LlmClient;
import com.codeagent.plan.ExecutionPlan;
import com.codeagent.plan.EvidenceType;
import com.codeagent.plan.PlanStateStore;
import com.codeagent.plan.Planner;
import com.codeagent.plan.Task;
import com.codeagent.plan.TaskResourceClaims;
import com.codeagent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlanExecuteRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void boundResumeImportsOnlyInterruptedTaskAndAppliesCurrentConstraintToPendingSuccessor() throws Exception {
        var registry=new ToolRegistry(); registry.setProjectPath(tempDir.toString());
        var client=new RecordingClient();
        try(var sessions=SessionStore.open(tempDir.resolve("bound-history"));
            var parent=sessions.create(new SessionStore.SessionCreateRequest(tempDir,"test","test",null,"plan","agent"))) {
            var plans=new PlanStateStore(tempDir.resolve("bound.db"));
            parent.append(new SessionEventDraft(SessionEvent.Types.EXECUTION_START,"runtime","execution-coordinator","runtime-execution",false,
                    SessionEvent.SurfaceOperation.none(),JSON.createObjectNode().put("executionId","original-execution").put("ordinal",1)));
            var attempts=new com.codeagent.plan.TaskRecoveryStore(plans.dbPath());
            var plan=new ExecutionPlan("bound-plan","original goal");
            var done=new Task("done","already completed",Task.TaskType.ANALYSIS); done.markCompleted("previous result");
            var interrupted=new Task("interrupted","inspect first",Task.TaskType.ANALYSIS); interrupted.markStarted();
            var pending=new Task("pending","inspect second",Task.TaskType.ANALYSIS); pending.addDependency("interrupted");
            plan.addTask(done);plan.addTask(interrupted);plan.addTask(pending);assertTrue(plan.computeExecutionOrder());plan.markStarted();
            plans.savePlan(tempDir,parent.sessionId(),"original-execution","old authorization",plan);
            String oldChildId;
            com.codeagent.model.recovery.TaskAttemptBinding source;
            try(var child=parent.createChild("plan","task:interrupted")) {
                oldChildId=child.sessionId();
                source=attempts.bindAttempt(tempDir,parent.sessionId(),"original-execution",plan.getId(),"interrupted",child.sessionId(),null,"old-owner",1);
                var p=JSON.createObjectNode();p.set("message",JSON.valueToTree(LlmClient.Message.assistant("historical observation about first task",
                        List.of(new LlmClient.ToolCall("old-read",new LlmClient.ToolCall.Function("list_dir","{\"path\":\".\"}"))))));
                child.append(new SessionEventDraft(SessionEvent.Types.ASSISTANT_MESSAGE,"plan","task:interrupted","llm_response",false,SessionEvent.SurfaceOperation.append(),p));
                var observed=JSON.createObjectNode().put("invocationId","old-read");observed.set("message",JSON.valueToTree(LlmClient.Message.tool("old-read","historical directory observation")));
                child.append(new SessionEventDraft(SessionEvent.Types.TOOL_RESULT,"plan","task:interrupted","tool_execution",false,SessionEvent.SurfaceOperation.append(),observed));
            }
            var agent=new PlanExecuteAgent(client,registry,new FailingIfCalledPlanner(client),null,
                    (goal,p)->PlanExecuteAgent.PlanReviewDecision.execute(),new PrintStream(new ByteArrayOutputStream()),PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(plans);agent.setParentConversationContext(parentContext(parent));
            agent.setTaskRecoveryStore(attempts);agent.setTaskTranscriptReader(new com.codeagent.history.TaskTranscriptReader(sessions,attempts));
            agent.setTaskRecoveryIdentity(new com.codeagent.model.recovery.TaskExecutionIdentity("original-execution",parent.sessionId(),"current-owner",2));
            var result=agent.resumeBoundPlanExecution(plan.getId(),"original-execution","continue; ONLY_INSPECT_CURRENT",null);
            assertEquals(com.codeagent.runtime.execution.ExecutionOutcome.SUCCEEDED,result.outcome(),result.displayResult());
            assertEquals(2,client.snapshots.size());
            assertTrue(joinedPrompt(client.snapshots.get(0)).contains("historical observation about first task"));
            assertTrue(client.snapshots.get(0).stream().anyMatch(m->"tool".equals(m.role())&&"old-read".equals(m.toolCallId())));
            assertTrue(client.snapshots.stream().allMatch(m->joinedPrompt(m).contains("ONLY_INSPECT_CURRENT")));
            assertFalse(joinedPrompt(client.snapshots.get(1)).contains("historical observation about first task"));
            var bindings=attempts.findTaskAttempts(tempDir,parent.sessionId(),"original-execution",plan.getId(),"interrupted");
            assertEquals(2,bindings.size());assertEquals(source.attemptId(),bindings.get(1).sourceAttemptId());
            assertTrue(sessions.readSnapshot(bindings.get(1).childSessionId(),tempDir,parent.sessionId(),-1).events().stream().anyMatch(e->"recovery_import".equals(e.source())));
            assertEquals(2,sessions.readSnapshot(oldChildId,tempDir,parent.sessionId(),-1).lastSequence());
        } finally {registry.closeWorkspaceResources();}
    }

    private Planner fourTaskPlanner(LlmClient client, AtomicInteger replans) {
        return new Planner(client, new PrintStream(new ByteArrayOutputStream())) {
            @Override public ExecutionPlan createPlan(String goal) {
                var plan = new ExecutionPlan("parallel-plan", goal);
                for (int i = 0; i < 4; i++) plan.addTask(new Task("task_" + i, "read " + i, Task.TaskType.FILE_READ));
                assertTrue(plan.computeExecutionOrder());
                return plan;
            }
            @Override public ExecutionPlan replan(ExecutionPlan failed, String reason, String context) {
                replans.incrementAndGet();
                throw new AssertionError("recovery initialization must not trigger replan");
            }
        };
    }

    @Test
    void defaultRecoveryStoreBindsFourRealParallelTasksBeforeTheirFirstChats() throws Exception {
        var registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (var sessions = SessionStore.open(tempDir.resolve("parallel-history"));
             var parent = sessions.create(new SessionStore.SessionCreateRequest(tempDir, "test", "model", null, "plan", "agent"))) {
            var plans = new PlanStateStore(tempDir.resolve("parallel.db"));
            var chats = new AtomicInteger();
            var replans = new AtomicInteger();
            var allFirstChats = new java.util.concurrent.CyclicBarrier(4);
            var client = new GLMClient("synthetic") {
                @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                    try {
                        allFirstChats.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        var store = com.codeagent.plan.TaskRecoveryStore.openReadOnly(plans.dbPath());
                        for (int i = 0; i < 4; i++) {
                            var bindings = store.findTaskAttempts(tempDir, parent.sessionId(), "parallel-execution", "parallel-plan", "task_" + i);
                            assertEquals(1, bindings.size(), "all actual child bindings precede any first-chat response");
                            assertTrue(sessions.readSnapshot(bindings.get(0).childSessionId(), tempDir, parent.sessionId(), -1).lastSequence() >= 2);
                        }
                    } catch (Exception error) { throw new IOException("parallel binding verification failed", error); }
                    chats.incrementAndGet();
                    return new ChatResponse("assistant", "completed", null, 1, 1);
                }
            };
            var agent = new PlanExecuteAgent(client, registry, fourTaskPlanner(client, replans), null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), new PrintStream(new ByteArrayOutputStream()), PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(plans);
            agent.setParentConversationContext(parentContext(parent));
            agent.setTaskRecoveryIdentity(new com.codeagent.model.recovery.TaskExecutionIdentity("parallel-execution", parent.sessionId(), "owner", 1));
            String result = agent.run("read independently", "read independently");
            assertTrue(result.contains("计划执行完成"), result);
            assertEquals(4, chats.get());
            assertEquals(0, replans.get());
            assertTrue(plans.readRecoveryState(tempDir, parent.sessionId()).get(0).tasks().stream().allMatch(t -> "COMPLETED".equals(t.recordedStatus())));
        }
    }

    @Test
    void parallelPreparationFailuresDrainAndInterruptEveryStartedTask() throws Exception {
        var registry = new ToolRegistry(); registry.setProjectPath(tempDir.toString());
        var client = new RecordingClient();
        var replans = new AtomicInteger();
        try (var sessions = SessionStore.open(tempDir.resolve("parallel-failure-history"));
             var parent = sessions.create(new SessionStore.SessionCreateRequest(tempDir, "test", "model", null, "plan", "agent"))) {
            var plans = new PlanStateStore(tempDir.resolve("parallel-failure.db"));
            var allBindings = new java.util.concurrent.CyclicBarrier(4);
            var finished = new AtomicInteger();
            var attempts = new com.codeagent.plan.TaskRecoveryStore(plans.dbPath()) {
                @Override public com.codeagent.model.recovery.TaskAttemptBinding bindAttempt(Path workspace, String session, String execution,
                        String plan, String task, String child, String source, String owner, int attempt) throws java.sql.SQLException {
                    try { allBindings.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception error) { throw new java.sql.SQLException("fixture barrier failed", error); }
                    finally { finished.incrementAndGet(); }
                    throw new java.sql.SQLException("synthetic preparation failure");
                }
            };
            var agent = new PlanExecuteAgent(client, registry, fourTaskPlanner(client, replans), null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), new PrintStream(new ByteArrayOutputStream()), PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(plans); agent.setParentConversationContext(parentContext(parent)); agent.setTaskRecoveryStore(attempts);
            agent.setTaskRecoveryIdentity(new com.codeagent.model.recovery.TaskExecutionIdentity("parallel-failure-execution", parent.sessionId(), "owner", 1));
            assertThrows(TaskRecoveryPreparationException.class, () -> agent.run("read", "read"));
            assertEquals(4, finished.get(), "all workers physically drained before defer");
            assertEquals(0, client.snapshots.size());
            assertEquals(0, replans.get());
            var snapshot = plans.readRecoveryState(tempDir, parent.sessionId()).get(0);
            assertEquals("RUNNING", snapshot.recordedStatus());
            assertTrue(snapshot.tasks().stream().allMatch(t -> "INTERRUPTED".equals(t.recordedStatus())), "no orphan RUNNING task after preparation-only batch failure");
        }
    }

    @Test
    void exactAttemptIsDurableBeforeFirstTaskChatAndRealChildGroupsAreReadable() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history-binding"));
             var session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir,"glm","test",null,"plan","agent"))) {
            var plans = new PlanStateStore(tempDir.resolve("binding-plans.db"));
            var attempts = new com.codeagent.plan.TaskRecoveryStore(plans.dbPath());
            var chats = new AtomicInteger();
            var client = new GLMClient("synthetic") {
                @Override public ChatResponse chat(List<Message> messages,List<Tool> tools,StreamListener listener) throws IOException {
                    try {
                        var bound=attempts.findTaskAttempts(tempDir,session.sessionId(),"exec-binding","plan-new","task_1");
                        assertEquals(1,bound.size(),"child binding must exist before chat");
                        var snapshot=sessions.readSnapshot(bound.get(0).childSessionId(),tempDir,session.sessionId(),-1);
                        assertTrue(snapshot.lastSequence()>=2,"current system/input persisted after binding");
                    } catch (java.sql.SQLException e) { throw new IOException("test",e); }
                    if(chats.incrementAndGet()==1){
                        return new ChatResponse("assistant","checking",List.of(
                                new ToolCall("read-call",new ToolCall.Function("list_dir","{\"path\":\".\"}"))),1,1);
                    }
                    return new ChatResponse("assistant","done",null,1,1);
                }
            };
            var agent = new PlanExecuteAgent(client,registry,new SingleTaskPlanner(client),null,
                    (goal,plan)->PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream()),PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(plans);agent.setParentConversationContext(parentContext(session));
            agent.setTaskRecoveryIdentity(new com.codeagent.model.recovery.TaskExecutionIdentity(
                    "exec-binding",session.sessionId(),"owner",3));
            agent.setTaskRecoveryStore(attempts);
            agent.run("请读取当前目录","请读取当前目录");
            assertEquals(2,chats.get());
            var binding=attempts.findTaskAttempts(tempDir,session.sessionId(),"exec-binding","plan-new","task_1").get(0);
            var transcript=new com.codeagent.history.TaskTranscriptReader(sessions,attempts).read(binding,-1);
            assertTrue(transcript.groups().stream().anyMatch(g->g.messages().size()==2
                    &&"read-call".equals(g.messages().get(1).toolCallId())));
            assertEquals(1,attempts.readCheckpoint(binding).facts().size());
            assertEquals(com.codeagent.model.recovery.RecoveryFact.VerificationClass.HISTORICAL,
                    attempts.readCheckpoint(binding).facts().get(0).verificationClass());
        }
    }

    @Test
    void bindingFailurePreventsFirstTaskModelAndTool() throws Exception {
        var client = new RecordingClient();var registry = new ToolRegistry();registry.setProjectPath(tempDir.toString());
        try(var sessions=SessionStore.open(tempDir.resolve("history-binding-fail"));
            var session=sessions.create(new SessionStore.SessionCreateRequest(tempDir,"glm","test",null,"plan","agent"))) {
            var plans=new PlanStateStore(tempDir.resolve("binding-fail.db"));
            var failing=new com.codeagent.plan.TaskRecoveryStore(plans.dbPath()){
                @Override public synchronized com.codeagent.model.recovery.TaskAttemptBinding bindAttempt(
                        Path workspace,String parent,String execution,String plan,String task,String child,String source,String owner,int attempt)throws java.sql.SQLException {
                    throw new java.sql.SQLException("synthetic private detail");
                }
            };
            var agent=new PlanExecuteAgent(client,registry,new SingleTaskPlanner(client),null,
                    (goal,plan)->PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream()),PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(plans);agent.setParentConversationContext(parentContext(session));agent.setTaskRecoveryStore(failing);
            agent.setTaskRecoveryIdentity(new com.codeagent.model.recovery.TaskExecutionIdentity("exec-binding-fail",session.sessionId(),"owner",1));
            assertThrows(com.codeagent.agent.TaskRecoveryPreparationException.class,()->agent.run("goal","goal"));
            assertTrue(client.snapshots.isEmpty());
            var snapshot=plans.readRecoveryState(tempDir,session.sessionId());
            assertEquals("RUNNING",snapshot.get(0).recordedStatus());
            assertEquals("INTERRUPTED",snapshot.get(0).tasks().get(0).recordedStatus());
        }
    }

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
    void terminalPlanConvergesRuntimeExecutionWithoutReplanning() throws Exception {
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history-terminal"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            PlanStateStore store = new PlanStateStore(tempDir.resolve("terminal-plans.db"));
            ExecutionPlan persisted = singleTaskPlan("plan-terminal", "已经完成的任务");
            persisted.markStarted();
            persisted.getTask("task_1").markCompleted("durable-result");
            persisted.markCompleted();
            persisted.setSummary("durable-summary");
            store.savePlan(tempDir, session.sessionId(), "exec-terminal", "raw input", persisted);

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
            agent.setRuntimeExecutionId("exec-terminal");

            AgentExecutionResult result = agent.runOrResumeExecution(
                    "不应重新执行", "不应重新执行");

            assertEquals(com.codeagent.runtime.execution.ExecutionOutcome.SUCCEEDED,
                    result.outcome());
            assertTrue(result.result().contains("durable-summary"), result.result());
            assertEquals(0, planner.createCalls.get(), "终态 Plan 只能收敛 Runtime，不能重新规划");
            assertTrue(client.snapshots.isEmpty(), "终态 Plan 收敛不得再次调用 LLM");
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
    void interruptedDiffTaskUsesPersistedPreCrashBaseline() throws Exception {
        Path source = tempDir.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "before\n");
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history-diff-resume"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            PlanStateStore store = new PlanStateStore(tempDir.resolve("diff-resume.db"));
            ExecutionPlan persisted = new ExecutionPlan("plan-diff-resume", "恢复文件修改");
            Task interrupted = new Task(
                    "task_1", "修改 src/App.java", Task.TaskType.FILE_WRITE, List.of(),
                    new TaskResourceClaims(List.of("src/App.java"), List.of("src/App.java"), false),
                    List.of("文件已修改"), Set.of(EvidenceType.DIFF));
            persisted.addTask(interrupted);
            persisted.computeExecutionOrder();
            store.savePlan(tempDir, session.sessionId(), persisted);
            TaskWorkspaceDiffTracker baseline = TaskWorkspaceDiffTracker.start(
                    tempDir, interrupted.getResourceClaims());
            store.saveTaskDiffBaseline(
                    persisted.getId(), interrupted.getId(),
                    JSON.writeValueAsString(baseline.baseline()));
            persisted.markStarted();
            store.checkpointPlan(persisted);
            interrupted.markStarted();
            store.checkpointTask(persisted.getId(), interrupted);
            Files.writeString(source, "after crash\n");

            PlanExecuteAgent agent = new PlanExecuteAgent(
                    client, registry, new FailingIfCalledPlanner(client), null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream()), PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(store);
            agent.setParentConversationContext(parentContext(session));

            String result = agent.resumeActivePlan();

            assertTrue(result.contains("计划执行完成"), result);
            assertEquals("after crash\n", Files.readString(source));
            assertEquals(1, client.snapshots.size(), "恢复时不应为了制造 DIFF 重写文件");
        }
    }

    @Test
    void recoveredReviewUsesOriginalSourceSnapshot() throws Exception {
        assertRecoveredReview(true);
    }

    @Test
    void legacyHashOnlyRecoveryCannotApproveChangedText() throws Exception {
        assertRecoveredReview(false);
    }

    private void assertRecoveredReview(boolean persistText) throws Exception {
        Path source = tempDir.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "original-source\n");
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        var config = new com.codeagent.snapshot.SnapshotConfig(true, tempDir.resolve("snapshots"),
                50, List.of(".git", "target"));
        try (var snapshots = new com.codeagent.snapshot.SnapshotService(
                new com.codeagent.snapshot.SideGitManager(tempDir, config));
             SessionStore sessions = SessionStore.open(tempDir.resolve("review-history"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            registry.setSnapshotService(snapshots);
            PlanStateStore store = new PlanStateStore(tempDir.resolve("review-plans.db"));
            ExecutionPlan plan = new ExecutionPlan("review-resume", "recover edit");
            Task task = new Task("edit", "edit source", Task.TaskType.FILE_WRITE, List.of(),
                    new TaskResourceClaims(List.of("src/App.java"), List.of("src/App.java"), false),
                    List.of("source updated"), Set.of(EvidenceType.DIFF));
            plan.addTask(task);
            plan.computeExecutionOrder();
            store.savePlan(tempDir, session.sessionId(), plan);
            TaskWorkspaceDiffTracker tracker = persistText
                    ? TaskWorkspaceDiffTracker.start(tempDir, task.getResourceClaims(), snapshots.manager())
                    : TaskWorkspaceDiffTracker.start(tempDir, task.getResourceClaims());
            store.saveTaskDiffBaseline(plan.getId(), task.getId(), JSON.writeValueAsString(tracker.baseline()));
            plan.markStarted();
            store.checkpointPlan(plan);
            task.markStarted();
            store.checkpointTask(plan.getId(), task);
            Files.writeString(source, "after-crash\n");
            // A fresh manager simulates losing all process-local baseline bytes.
            try (var restoredSnapshots = new com.codeagent.snapshot.SnapshotService(
                    new com.codeagent.snapshot.SideGitManager(tempDir, config))) {
                registry.setSnapshotService(restoredSnapshots);
                PlanExecuteAgent agent = new PlanExecuteAgent(client, registry, new FailingIfCalledPlanner(client), null,
                        (goal, p) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                        new PrintStream(new ByteArrayOutputStream()), PipelineOptions.FULL_PRESET);
                agent.setPlanStateStore(store);
                agent.setParentConversationContext(parentContext(session));
                String result = agent.resumeActivePlan();
                if (persistText) {
                    assertTrue(result.contains("计划执行完成"), result);
                    assertEquals(2, client.snapshots.size());
                    String review = client.snapshots.get(1).stream().map(LlmClient.Message::content)
                            .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining("\n"));
                    assertTrue(review.contains("-original-source\n+after-crash"), review);
                } else {
                    assertTrue(result.contains("未验证"), result);
                    assertTrue(result.contains("diff text unavailable"), result);
                    assertEquals(1, client.snapshots.size());
                }
            }
        }
    }

    @Test
    void interruptedDiffTaskWithoutBaselineStopsBeforeWorkerCall() throws Exception {
        Path source = tempDir.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "after crash\n");
        RecordingClient client = new RecordingClient();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history-missing-diff-baseline"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            PlanStateStore store = new PlanStateStore(tempDir.resolve("missing-diff-baseline.db"));
            ExecutionPlan persisted = new ExecutionPlan("plan-missing-diff-baseline", "恢复文件修改");
            Task interrupted = new Task(
                    "task_1", "修改 src/App.java", Task.TaskType.FILE_WRITE, List.of(),
                    new TaskResourceClaims(List.of("src/App.java"), List.of("src/App.java"), false),
                    List.of("文件已修改"), Set.of(EvidenceType.DIFF));
            persisted.addTask(interrupted);
            persisted.computeExecutionOrder();
            store.savePlan(tempDir, session.sessionId(), persisted);
            persisted.markStarted();
            store.checkpointPlan(persisted);
            interrupted.markStarted();
            store.checkpointTask(persisted.getId(), interrupted);

            PlanExecuteAgent agent = new PlanExecuteAgent(
                    client, registry, new FailingIfCalledPlanner(client), null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream()), PipelineOptions.PLAN_PRESET);
            agent.setPlanStateStore(store);
            agent.setParentConversationContext(parentContext(session));

            String result = agent.resumeActivePlan();

            assertTrue(result.contains("persisted diff baseline missing"), result);
            assertEquals(0, client.snapshots.size(),
                    "缺少恢复 baseline 时不得调用 Worker/LLM 或产生新的工具副作用");
            assertEquals("after crash\n", Files.readString(source));
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
            if (messages.stream().anyMatch(message -> message.content() != null
                    && message.content().contains("Task initial baseline to final workspace patch"))) {
                return new ChatResponse("assistant", "{\"approved\":true,\"summary\":\"ok\",\"issues\":[],\"suggestions\":[]}", null, 10, 2);
            }
            return new ChatResponse("assistant", "剩余任务完成", null, 10, 2);
        }
    }
}
