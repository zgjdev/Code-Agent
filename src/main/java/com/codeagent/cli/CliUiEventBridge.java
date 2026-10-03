package com.codeagent.cli;

import com.codeagent.render.Renderer;
import com.codeagent.render.StatusInfo;
import com.codeagent.runtime.execution.RuntimeExecution;
import com.codeagent.runtime.interaction.InteractionKind;
import com.codeagent.runtime.interaction.InteractionRequest;

import java.io.PrintStream;

/** Serializes scheduler and interaction notifications onto the CLI renderer surface. */
final class CliUiEventBridge {
    private final Renderer renderer;
    private final PrintStream out;

    CliUiEventBridge(Renderer renderer) {
        this.renderer = renderer;
        this.out = renderer.stream();
    }

    synchronized void queued(RuntimeExecution execution) {
        out.printf("🕓 已入队 %s（会话序号 #%d）%n%n", execution.id(), execution.ordinal());
    }

    synchronized void started(RuntimeExecution execution, StatusInfo status) {
        renderer.beginTurn();
        renderer.updateStatus(status);
        out.printf("▶️ 开始执行 %s%n", execution.id());
    }

    synchronized void interaction(InteractionRequest request) {
        out.println();
        out.println(request.prompt());
        if (request.kind() == InteractionKind.PLAN_REVIEW) {
            out.println("[Enter/y] 执行  [cancel] 取消；其它文本将作为补充要求");
        } else {
            out.println("[Enter/y] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m <JSON>] 修改参数");
        }
        out.println();
    }

    synchronized void completed(RuntimeExecution execution, String displayResult, StatusInfo idleStatus) {
        renderer.updateStatus(idleStatus);
        if (displayResult != null && !displayResult.isBlank()) {
            out.println(displayResult);
        }
        out.printf("%n%s %s · %s%n%n",
                execution.status().terminal() ? "■" : "□",
                execution.id(),
                execution.status().databaseValue());
    }

    synchronized void message(String message) {
        out.println(message);
        out.println();
    }
}
