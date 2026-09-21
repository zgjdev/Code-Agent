package com.codeagent.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlanStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsDagResourcesEvidenceAndCompletedState() throws Exception {
        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
        ExecutionPlan plan = new ExecutionPlan("plan_1", "修复登录并测试");
        Task first = new Task(
                "task_1",
                "修改登录实现",
                Task.TaskType.FILE_WRITE,
                List.of(),
                new TaskResourceClaims(
                        List.of("src/main/java/LoginService.java"),
                        List.of("src/main/java/LoginService.java"),
                        false),
                List.of("异常分支已覆盖"),
                Set.of(EvidenceType.DIFF));
        Task second = new Task(
                "task_2",
                "运行测试",
                Task.TaskType.VERIFICATION,
                List.of("task_1"),
                new TaskResourceClaims(List.of("src/test/java/"), List.of(), false),
                List.of("测试通过"),
                Set.of(EvidenceType.TEST));
        plan.addTask(first);
        plan.addTask(second);
        assertTrue(plan.computeExecutionOrder());

        store.savePlan(tempDir, plan);
        plan.markStarted();
        store.checkpointPlan(plan);
        first.markCompleted("diff ok");
        store.checkpointTask(plan.getId(), first);

        PlanStateStore.ResumeCandidate candidate = store
                .findResumable(tempDir, "修复登录并测试")
                .orElseThrow();

        ExecutionPlan restored = candidate.plan();
        assertEquals(ExecutionPlan.PlanStatus.RUNNING, restored.getStatus());
        assertEquals(List.of("task_1", "task_2"), restored.getExecutionOrder());

        Task restoredFirst = restored.getTask("task_1");
        assertEquals(Task.TaskStatus.COMPLETED, restoredFirst.getStatus());
        assertEquals("diff ok", restoredFirst.getResult());
        assertEquals(List.of("src/main/java/LoginService.java"),
                restoredFirst.getResourceClaims().readPaths());
        assertEquals(List.of("src/main/java/LoginService.java"),
                restoredFirst.getResourceClaims().writePaths());
        assertEquals(List.of("异常分支已覆盖"), restoredFirst.getAcceptanceCriteria());
        assertEquals(Set.of(EvidenceType.DIFF), restoredFirst.getRequiredEvidence());

        Task restoredSecond = restored.getTask("task_2");
        assertEquals(List.of("task_1"), restoredSecond.getDependencies());
        assertTrue(restoredFirst.getDependents().contains("task_2"));
        assertEquals(Set.of(EvidenceType.TEST), restoredSecond.getRequiredEvidence());
    }

    @Test
    void convertsRunningAndReviewingTasksToInterruptedOnResume() throws Exception {
        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
        ExecutionPlan plan = new ExecutionPlan("plan_2", "恢复任务");
        Task running = new Task("task_1", "运行中", Task.TaskType.FILE_READ);
        Task reviewing = new Task("task_2", "审查中", Task.TaskType.ANALYSIS);
        plan.addTask(running);
        plan.addTask(reviewing);
        plan.computeExecutionOrder();

        store.savePlan(tempDir, plan);
        plan.markStarted();
        store.checkpointPlan(plan);

        running.markStarted();
        store.checkpointTask(plan.getId(), running);
        reviewing.markStarted();
        reviewing.markReviewing();
        store.checkpointTask(plan.getId(), reviewing);

        PlanStateStore.ResumeCandidate candidate = store
                .findResumable(tempDir, "恢复任务")
                .orElseThrow();

        assertEquals(Task.TaskStatus.INTERRUPTED,
                candidate.plan().getTask("task_1").getStatus());
        assertEquals(Task.TaskStatus.INTERRUPTED,
                candidate.plan().getTask("task_2").getStatus());
        assertEquals(Set.of("task_1", "task_2"), candidate.interruptedTaskIds());
        assertEquals(2, candidate.plan().getExecutableTasks().size());
    }

    @Test
    void ignoresDifferentGoalAndTerminalPlans() throws Exception {
        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
        ExecutionPlan plan = new ExecutionPlan("plan_3", "原目标");
        plan.addTask(new Task("task_1", "分析", Task.TaskType.ANALYSIS));
        plan.computeExecutionOrder();

        store.savePlan(tempDir, plan);
        plan.markStarted();
        store.checkpointPlan(plan);

        assertTrue(store.findResumable(tempDir, "另一个目标").isEmpty());

        plan.markCompleted();
        store.checkpointPlan(plan);
        assertTrue(store.findResumable(tempDir, "原目标").isEmpty());
    }
    @Test
    void matchesSubmittedResumeKeyIndependentlyFromExpandedGoal() throws Exception {
        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
        ExecutionPlan plan = new ExecutionPlan(
                "plan_resume_key",
                "用户原始输入\n\n<expanded-file>旧文件内容</expanded-file>");
        plan.addTask(new Task("task_1", "读取文件", Task.TaskType.FILE_READ));
        plan.computeExecutionOrder();

        store.savePlan(tempDir, "用户原始输入", plan);
        plan.markStarted();
        store.checkpointPlan(plan);

        assertTrue(store.findResumable(tempDir, "用户原始输入").isPresent());
        assertTrue(store.findResumable(
                tempDir,
                "用户原始输入\n\n<expanded-file>新文件内容</expanded-file>").isEmpty());
    }

}
