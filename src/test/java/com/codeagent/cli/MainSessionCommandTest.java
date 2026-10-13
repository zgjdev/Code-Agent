package com.codeagent.cli;

import com.codeagent.history.SessionStore;
import com.codeagent.plan.ExecutionPlan;
import com.codeagent.plan.PlanStateStore;
import com.codeagent.plan.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainSessionCommandTest {

    @Test
    void automaticResumeIsEnabledByDefault() {
        assertTrue(Main.isAutomaticSessionResumeEnabled(null, null));
    }

    @Test
    void systemPropertyCanDisableAutomaticResume() {
        assertFalse(Main.isAutomaticSessionResumeEnabled("off", "auto"));
    }

    @Test
    void environmentCanDisableAutomaticResume() {
        assertFalse(Main.isAutomaticSessionResumeEnabled(null, "off"));
    }

    @Test
    void unknownValuesRetainSafeDefault() {
        assertTrue(Main.isAutomaticSessionResumeEnabled("unexpected", null));
    }
    @Test
    void activePlanNoticeIsScopedToCurrentSession(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("codeagent.plan.dir");
        Path planDir = tempDir.resolve("plans");
        System.setProperty("codeagent.plan.dir", planDir.toString());
        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history"));
             SessionStore.SessionHandle sessionA = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"));
             SessionStore.SessionHandle sessionB = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            PlanStateStore store = PlanStateStore.openDefault();
            ExecutionPlan plan = new ExecutionPlan("plan-a", "任务");
            plan.addTask(new Task("task_1", "分析", Task.TaskType.ANALYSIS));
            assertTrue(plan.computeExecutionOrder());
            store.savePlan(tempDir, sessionA.sessionId(), plan);

            assertTrue(Main.activePlanNotice(tempDir, sessionA).contains("/plan resume"));
            assertTrue(Main.activePlanNotice(tempDir, sessionB).isBlank());
        } finally {
            if (previous == null) {
                System.clearProperty("codeagent.plan.dir");
            } else {
                System.setProperty("codeagent.plan.dir", previous);
            }
        }
    }

}
