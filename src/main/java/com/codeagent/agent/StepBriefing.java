package com.codeagent.agent;

import com.codeagent.plan.Task;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 下行简报：编排器交给步骤执行体的全部上下文，唯一渲染点。
 * 替代此前 PlanExecuteAgent.buildTaskContext 与 AgentOrchestrator.buildStepContext 两份实现。
 */
public record StepBriefing(
        String goal,
        Task task,
        List<Task> completedDependencies,
        List<String> trustedDependencyUrls,
        String retryFeedback) {

    public String render() {
        StringBuilder briefing = new StringBuilder();
        briefing.append("总目标：").append(goal).append("\n");
        briefing.append("当前任务：").append(task.getId())
                .append(" / ").append(task.getDescription())
                .append(" / 类型=").append(task.getType())
                .append("\n");

        briefing.append("依赖任务结果：\n");
        if (completedDependencies.isEmpty()) {
            briefing.append("无\n");
        } else {
            for (Task dependency : completedDependencies) {
                briefing.append("- ").append(dependency.getId())
                        .append(" / ").append(dependency.getDescription())
                        .append(" / 状态=").append(dependency.getStatus())
                        .append("\n");
                if (dependency.getResult() != null && !dependency.getResult().isBlank()) {
                    briefing.append(dependency.getResult()).append("\n");
                }
            }
        }

        Set<String> urls = new LinkedHashSet<>(trustedDependencyUrls);
        if (!urls.isEmpty()) {
            briefing.append("依赖分支经 web_search 验证的 URL（可供当前任务抓取/导航）：\n");
            urls.forEach(url -> briefing.append("- ").append(url).append("\n"));
        }

        if (retryFeedback != null && !retryFeedback.isBlank()) {
            briefing.append("之前的结果被审查拒绝，原因：\n");
            briefing.append(retryFeedback).append("\n");
        }

        return briefing.toString();
    }
}
