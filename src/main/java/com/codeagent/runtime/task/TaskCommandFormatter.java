package com.codeagent.runtime.task;

import com.codeagent.runtime.execution.CancelRequestResult;
import com.codeagent.runtime.execution.RuntimeExecution;
import com.codeagent.runtime.execution.RuntimeExecutionQueue;

import java.sql.SQLException;
import java.util.List;

public final class TaskCommandFormatter {
    private TaskCommandFormatter() {}

    public static String handle(DurableTaskManager manager, String payload) {
        String normalized = payload == null || payload.isBlank() ? "list" : payload.trim();
        if (normalized.equalsIgnoreCase("list")) {
            return formatList(manager.list(20));
        }
        if (normalized.regionMatches(true, 0, "list ", 0, 5)) {
            return formatList(manager.list(parseLimit(normalized.substring(5).trim(), 20)));
        }
        if (normalized.regionMatches(true, 0, "add ", 0, 4)) {
            DurableTask task = manager.enqueue(normalized.substring(4).trim());
            return "✅ 后台任务已提交: " + task.id() + "\n   /task log " + task.id();
        }
        if (normalized.regionMatches(true, 0, "cancel ", 0, 7)) {
            String id = normalized.substring(7).trim();
            return manager.cancel(id)
                    ? "⏹️ 已请求取消后台任务: " + id
                    : "❌ 未找到可取消的后台任务: " + id;
        }
        if (normalized.regionMatches(true, 0, "log ", 0, 4)) {
            return manager.find(normalized.substring(4).trim())
                    .map(TaskCommandFormatter::formatLog)
                    .orElse("❌ 未找到后台任务: " + normalized.substring(4).trim());
        }
        return """
                ❌ 未知 /task 子命令: %s
                可用命令：
                  /task
                  /task list [N]
                  /task add <任务内容>
                  /task cancel <task_id>
                  /task log <task_id>
                """.formatted(payload).trim();
    }

    public static String handle(
            RuntimeExecutionQueue queue,
            String sessionId,
            String payload) {
        String normalized = payload == null || payload.isBlank() ? "list" : payload.trim();
        try {
            if (normalized.equalsIgnoreCase("list")) {
                return formatExecutions(queue.list(20));
            }
            if (normalized.regionMatches(true, 0, "list ", 0, 5)) {
                return formatExecutions(queue.list(
                        parseLimit(normalized.substring(5).trim(), 20)));
            }
            if (normalized.regionMatches(true, 0, "add ", 0, 4)) {
                String task = normalized.substring(4).trim();
                RuntimeExecution execution = queue.submit(sessionId, task, null);
                return "✅ 任务已入队: " + execution.id()
                        + "\n   session ordinal: " + execution.ordinal()
                        + "\n   /task log " + execution.id();
            }
            if (normalized.regionMatches(true, 0, "cancel ", 0, 7)) {
                String id = normalized.substring(7).trim();
                CancelRequestResult result = queue.cancel(id);
                return switch (result) {
                    case REQUESTED, ALREADY_REQUESTED -> "⏹️ 已请求取消任务: " + id;
                    case ALREADY_TERMINAL -> "ℹ️ 任务已是终态: " + id;
                    case NOT_FOUND -> "❌ 未找到任务: " + id;
                };
            }
            if (normalized.regionMatches(true, 0, "log ", 0, 4)) {
                String id = normalized.substring(4).trim();
                return queue.find(id).map(TaskCommandFormatter::formatExecution)
                        .orElse("❌ 未找到任务: " + id);
            }
        } catch (SQLException | IllegalArgumentException e) {
            return "❌ /task 操作失败: " + e.getMessage();
        }
        return """
                ❌ 未知 /task 子命令: %s
                可用命令：
                  /task
                  /task list [N]
                  /task add <任务内容>
                  /task cancel <execution_id>
                  /task log <execution_id>
                """.formatted(payload).trim();
    }

    public static String formatExecutions(List<RuntimeExecution> executions) {
        if (executions == null || executions.isEmpty()) {
            return "📭 暂无任务";
        }
        StringBuilder result = new StringBuilder("📋 最近 ")
                .append(executions.size()).append(" 个任务：\n");
        for (RuntimeExecution execution : executions) {
            String input = execution.submittedInput().replace('\n', ' ');
            if (input.length() > 60) {
                input = input.substring(0, 57) + "...";
            }
            result.append("   ").append(execution.id())
                    .append("  ").append(execution.status().databaseValue())
                    .append("  #").append(execution.ordinal())
                    .append("  ").append(input).append('\n');
        }
        return result.toString().trim();
    }

    public static String formatExecution(RuntimeExecution execution) {
        StringBuilder result = new StringBuilder()
                .append("📋 任务 ").append(execution.id()).append('\n')
                .append("状态: ").append(execution.status().databaseValue()).append('\n')
                .append("会话: ").append(execution.sessionId()).append('\n')
                .append("序号: ").append(execution.ordinal()).append('\n')
                .append("创建: ").append(execution.createdAt()).append('\n');
        if (execution.selectedMode() != null) {
            result.append("模式: ").append(execution.selectedMode().name().toLowerCase()).append('\n');
        }
        result.append("\n任务:\n").append(execution.submittedInput()).append('\n');
        if (execution.error() != null && !execution.error().isBlank()) {
            result.append("\n错误:\n").append(execution.error()).append('\n');
        }
        if (execution.result() != null && !execution.result().isBlank()) {
            result.append("\n结果:\n").append(execution.result()).append('\n');
        }
        return result.toString().trim();
    }

    public static String formatList(List<DurableTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "📭 暂无后台任务";
        }
        StringBuilder sb = new StringBuilder("📋 最近 ").append(tasks.size()).append(" 个后台任务：\n");
        for (DurableTask task : tasks) {
            sb.append("   ")
                    .append(task.id())
                    .append("  ")
                    .append(task.status().value())
                    .append("  ")
                    .append(task.durationMs())
                    .append("ms  ")
                    .append(task.shortPrompt())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    public static String formatLog(DurableTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 后台任务 ").append(task.id()).append('\n');
        sb.append("状态: ").append(task.status().value()).append('\n');
        sb.append("创建: ").append(task.createdAt()).append('\n');
        if (task.startedAt() != null) {
            sb.append("开始: ").append(task.startedAt()).append('\n');
        }
        if (task.finishedAt() != null) {
            sb.append("结束: ").append(task.finishedAt()).append(" (").append(task.durationMs()).append("ms)\n");
        }
        sb.append("\n任务:\n").append(task.prompt()).append('\n');
        if (task.error() != null && !task.error().isBlank()) {
            sb.append("\n错误:\n").append(task.error()).append('\n');
        }
        if (task.result() != null && !task.result().isBlank()) {
            sb.append("\n结果:\n").append(task.result()).append('\n');
        }
        return sb.toString().trim();
    }

    private static int parseLimit(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
