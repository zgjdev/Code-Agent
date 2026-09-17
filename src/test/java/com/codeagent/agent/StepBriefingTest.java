package com.codeagent.agent;

import com.codeagent.plan.Task;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepBriefingTest {

    @Test
    void rendersGoalCurrentTaskAndType() {
        Task current = new Task("t2", "写单元测试", Task.TaskType.ANALYSIS);

        String output = briefing(current, List.of(), List.of(), null).render();

        assertTrue(output.contains("总目标：统一两种模式"), output);
        assertTrue(output.contains("t2 / 写单元测试 / 类型=ANALYSIS"), output);
    }

    @Test
    void keepsDependencyResultsInFull() {
        String longResult = "x".repeat(1200);
        Task dependency = completed("t1", "读取源码", longResult);

        String output = briefing(currentTask(), List.of(dependency), List.of(), null).render();

        assertTrue(output.contains("- t1 / 读取源码 / 状态=COMPLETED"), output);
        assertTrue(output.contains(longResult),
                "依赖结果不得截断：Team 侧原有的 500 字符截断应被移除");
    }

    @Test
    void reportsEmptyDependenciesExplicitly() {
        String output = briefing(currentTask(), List.of(), List.of(), null).render();

        assertTrue(output.contains("依赖任务结果：\n无\n"), output);
    }

    @Test
    void listsTrustedDependencyUrlsWhenPresent() {
        String output = briefing(currentTask(), List.of(),
                List.of("https://example.com/a", "https://example.com/b"), null).render();

        assertTrue(output.contains("依赖分支经 web_search 验证的 URL（可供当前任务抓取/导航）："), output);
        assertTrue(output.contains("- https://example.com/a"), output);
        assertTrue(output.contains("- https://example.com/b"), output);
    }

    @Test
    void omitsUrlSectionWhenNoTrustedUrls() {
        String output = briefing(currentTask(), List.of(), List.of(), null).render();

        assertFalse(output.contains("web_search 验证的 URL"), output);
    }

    @Test
    void omitsRetrySectionOnFirstAttempt() {
        String output = briefing(currentTask(), List.of(), List.of(), null).render();

        assertFalse(output.contains("被审查拒绝"), output);
    }

    @Test
    void includesRetryFeedbackWhenRetrying() {
        String output = briefing(currentTask(), List.of(), List.of(), "缺少边界条件覆盖").render();

        assertTrue(output.contains("之前的结果被审查拒绝，原因：\n缺少边界条件覆盖"), output);
    }

    private static Task currentTask() {
        return new Task("t2", "写单元测试", Task.TaskType.ANALYSIS);
    }

    private static Task completed(String id, String description, String result) {
        Task task = new Task(id, description, Task.TaskType.FILE_READ);
        task.markCompleted(result);
        return task;
    }

    private static StepBriefing briefing(Task task, List<Task> completedDependencies,
                                         List<String> trustedUrls, String retryFeedback) {
        return new StepBriefing("统一两种模式", task, completedDependencies, trustedUrls, retryFeedback);
    }
}
