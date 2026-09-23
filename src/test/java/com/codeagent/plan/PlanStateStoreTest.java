package com.codeagent.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlanStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsDagResourcesEvidenceAndCompletedStateBySession() throws Exception {
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

        store.savePlan(tempDir, "session-a", "不要联网，只修改本地登录模块", plan);
        plan.markStarted();
        store.checkpointPlan(plan);
        first.markCompleted("diff ok");
        store.checkpointTask(plan.getId(), first);

        PlanStateStore.ResumeCandidate candidate = store
                .findActive(tempDir, "session-a")
                .orElseThrow();

        assertEquals("不要联网，只修改本地登录模块", candidate.policyInput());

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
    void findByIdRestoresTerminalTaskResultsWithoutMakingPlanActive() throws Exception {
        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
        ExecutionPlan plan = singleTaskPlan("plan-terminal", "完成后用于 reconcile");
        store.savePlan(tempDir, "session-a", "原始用户授权边界", plan);
        plan.markStarted();
        store.checkpointPlan(plan);
        Task task = plan.getTask("task_1");
        task.markCompleted("persisted-result");
        store.checkpointTask(plan.getId(), task);
        plan.markCompleted();
        store.checkpointPlan(plan);

        PlanStateStore.StoredPlan stored = store.findById(plan.getId()).orElseThrow();

        assertEquals(ExecutionPlan.PlanStatus.COMPLETED, stored.plan().getStatus());
        assertEquals("persisted-result", stored.plan().getTask("task_1").getResult());
        assertEquals("原始用户授权边界", stored.info().policyInput());
        assertTrue(store.findActive(tempDir, "session-a").isEmpty());
    }

    @Test
    void sameWorkspaceAndSameGoalRemainIndependentAcrossSessions() throws Exception {
        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));

        ExecutionPlan first = singleTaskPlan("plan-a", "相同提示词");
        ExecutionPlan second = singleTaskPlan("plan-b", "相同提示词");

        store.savePlan(tempDir, "session-a", first);
        store.savePlan(tempDir, "session-b", second);

        assertEquals("plan-a", store.findActive(tempDir, "session-a").orElseThrow().plan().getId());
        assertEquals("plan-b", store.findActive(tempDir, "session-b").orElseThrow().plan().getId());
    }

    @Test
    void rejectsSecondActivePlanForSameSession() throws Exception {
        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
        store.savePlan(tempDir, "session-a", singleTaskPlan("plan-a", "第一个任务"));

        assertThrows(Exception.class,
                () -> store.savePlan(tempDir, "session-a", singleTaskPlan("plan-b", "第二个任务")));
    }

    @Test
    void activeInfoLookupDoesNotMutateRunningTaskBeforeExplicitResume() throws Exception {
        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
        ExecutionPlan plan = singleTaskPlan("plan-read-only", "只读检查");
        store.savePlan(tempDir, "session-a", plan);
        plan.markStarted();
        store.checkpointPlan(plan);
        Task task = plan.getTask("task_1");
        task.markStarted();
        store.checkpointTask(plan.getId(), task);

        assertTrue(store.findActiveInfo(tempDir, "session-a").isPresent());

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.dbPath());
             var ps = connection.prepareStatement(
                     "SELECT status FROM plan_tasks WHERE plan_id = ? AND task_id = ?")) {
            ps.setString(1, plan.getId());
            ps.setString(2, task.getId());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(Task.TaskStatus.RUNNING.name(), rs.getString("status"));
            }
        }

        PlanStateStore.ResumeCandidate resumed = store.findActive(tempDir, "session-a").orElseThrow();
        assertEquals(Task.TaskStatus.INTERRUPTED, resumed.plan().getTask("task_1").getStatus());
    }

    @Test
    void convertsRunningAndReviewingTasksToInterruptedOnSessionResume() throws Exception {
        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
        ExecutionPlan plan = new ExecutionPlan("plan_2", "恢复任务");
        Task running = new Task("task_1", "运行中", Task.TaskType.FILE_READ);
        Task reviewing = new Task("task_2", "审查中", Task.TaskType.ANALYSIS);
        plan.addTask(running);
        plan.addTask(reviewing);
        plan.computeExecutionOrder();

        store.savePlan(tempDir, "session-a", plan);
        plan.markStarted();
        store.checkpointPlan(plan);

        running.markStarted();
        store.checkpointTask(plan.getId(), running);
        reviewing.markStarted();
        reviewing.markReviewing();
        store.checkpointTask(plan.getId(), reviewing);

        PlanStateStore.ResumeCandidate candidate = store
                .findActive(tempDir, "session-a")
                .orElseThrow();

        assertEquals(Task.TaskStatus.INTERRUPTED,
                candidate.plan().getTask("task_1").getStatus());
        assertEquals(Task.TaskStatus.INTERRUPTED,
                candidate.plan().getTask("task_2").getStatus());
        assertEquals(Set.of("task_1", "task_2"), candidate.interruptedTaskIds());
        assertEquals(2, candidate.plan().getExecutableTasks().size());
    }

    @Test
    void abandonRemovesPlanFromActiveLookupAndAllowsReplacement() throws Exception {
        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
        ExecutionPlan first = singleTaskPlan("plan-a", "原任务");
        store.savePlan(tempDir, "session-a", first);
        first.markStarted();
        store.checkpointPlan(first);

        assertTrue(store.abandonActive(tempDir, "session-a"));
        assertTrue(store.findActive(tempDir, "session-a").isEmpty());

        ExecutionPlan replacement = singleTaskPlan("plan-b", "新任务");
        assertDoesNotThrow(() -> store.savePlan(tempDir, "session-a", replacement));
        assertEquals("plan-b", store.findActive(tempDir, "session-a").orElseThrow().plan().getId());
    }

    @Test
    void terminalPlansAreNotActive() throws Exception {
        PlanStateStore store = new PlanStateStore(tempDir.resolve("plans.db"));
        ExecutionPlan plan = singleTaskPlan("plan_3", "原目标");

        store.savePlan(tempDir, "session-a", plan);
        plan.markStarted();
        store.checkpointPlan(plan);
        assertTrue(store.findActive(tempDir, "session-a").isPresent());

        plan.markCompleted();
        store.checkpointPlan(plan);
        assertTrue(store.findActive(tempDir, "session-a").isEmpty());
    }

    @Test
    void migratesOldPromptBoundSchemaButDoesNotAutoBindLegacyRows() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE plan_runs (
                        id TEXT PRIMARY KEY,
                        workspace TEXT NOT NULL,
                        resume_key TEXT NOT NULL,
                        goal TEXT NOT NULL,
                        status TEXT NOT NULL,
                        summary TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE plan_tasks (
                        plan_id TEXT NOT NULL,
                        task_id TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        description TEXT NOT NULL,
                        type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        dependencies_json TEXT NOT NULL,
                        read_paths_json TEXT NOT NULL,
                        write_paths_json TEXT NOT NULL,
                        workspace_write INTEGER NOT NULL,
                        acceptance_criteria_json TEXT NOT NULL,
                        required_evidence_json TEXT NOT NULL,
                        result TEXT,
                        error TEXT,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(plan_id, task_id)
                    )
                    """);
            String workspace = tempDir.toAbsolutePath().normalize().toString().replace("'", "''");
            stmt.execute("""
                    INSERT INTO plan_runs(
                        id, workspace, resume_key, goal, status, created_at, updated_at
                    ) VALUES(
                        'legacy-plan', '%s', '相同提示词', '相同提示词', 'RUNNING', 'now', 'now'
                    )
                    """.formatted(workspace));
        }

        PlanStateStore store = new PlanStateStore(db);

        assertTrue(Files.exists(db));
        assertTrue(store.findActive(tempDir, "session-new").isEmpty(),
                "legacy prompt-bound row must not be guessed into a Session");
        assertDoesNotThrow(() -> store.savePlan(
                tempDir, "session-new", singleTaskPlan("new-plan", "相同提示词")));
    }

    private static ExecutionPlan singleTaskPlan(String id, String goal) {
        ExecutionPlan plan = new ExecutionPlan(id, goal);
        plan.addTask(new Task("task_1", "分析", Task.TaskType.ANALYSIS));
        assertTrue(plan.computeExecutionOrder());
        return plan;
    }
}
