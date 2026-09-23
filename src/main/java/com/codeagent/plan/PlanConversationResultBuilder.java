package com.codeagent.plan;

import java.util.ArrayList;
import java.util.List;

/** Builds the durable top-level semantic result for one terminal Plan. */
public final class PlanConversationResultBuilder {

    public String build(ExecutionPlan plan) {
        if (plan == null) {
            return "⚠️ 计划状态不可用。";
        }
        return switch (plan.getStatus()) {
            case COMPLETED -> completed(plan);
            case CANCELLED -> "⏹️ 计划已取消。";
            case FAILED -> failed(plan);
            case CREATED, RUNNING -> inProgress(plan);
        };
    }

    private String completed(ExecutionPlan plan) {
        List<Task> leaves = plan.getExecutionOrder().stream()
                .map(plan::getTask)
                .filter(task -> task != null && task.getDependents().isEmpty())
                .toList();
        List<String> lines = new ArrayList<>();
        for (Task task : leaves) {
            if (task.getResult() != null && !task.getResult().isBlank()) {
                lines.add("[" + task.getId() + "] " + task.getResult().trim());
            }
        }
        if (lines.isEmpty()) {
            List<String> order = plan.getExecutionOrder();
            for (int index = order.size() - 1; index >= 0; index--) {
                Task task = plan.getTask(order.get(index));
                if (task != null
                        && task.getStatus() == Task.TaskStatus.COMPLETED
                        && task.getResult() != null
                        && !task.getResult().isBlank()) {
                    lines.add("[" + task.getId() + "] " + task.getResult().trim());
                    break;
                }
            }
        }
        return lines.isEmpty()
                ? "✅ 计划执行完成！"
                : "✅ 计划执行完成！\n" + String.join("\n", lines);
    }

    private String failed(ExecutionPlan plan) {
        List<String> lines = new ArrayList<>();
        for (String taskId : plan.getExecutionOrder()) {
            Task task = plan.getTask(taskId);
            if (task == null) continue;
            if (task.getStatus() == Task.TaskStatus.FAILED
                    || task.getStatus() == Task.TaskStatus.UNVERIFIED) {
                String detail = task.getError();
                if (detail == null || detail.isBlank()) detail = task.getResult();
                if (detail != null && !detail.isBlank()) {
                    lines.add("[" + task.getId() + "] " + detail.trim());
                }
            }
        }
        return lines.isEmpty()
                ? "⚠️ 计划部分完成，有任务失败。"
                : "⚠️ 计划部分完成，有任务失败。\n" + String.join("\n", lines);
    }

    private String inProgress(ExecutionPlan plan) {
        return "⚠️ 计划尚未进入终态，当前状态: " + plan.getStatus();
    }
}
