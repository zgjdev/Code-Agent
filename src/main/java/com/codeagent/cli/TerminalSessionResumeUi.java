package com.codeagent.cli;

import com.codeagent.history.*;
import java.util.List;
import java.util.function.Consumer;

/** A pending-input state machine driven exclusively by Main's existing LineReader. */
final class TerminalSessionResumeUi {
    private sealed interface Pending permits Picker, History { }
    private record Picker(int offset, String query, List<SessionListEntry> entries) implements Pending { }
    private record History(String id, TranscriptCursor next, long watermark, boolean cleared,
                           boolean includeCleared) implements Pending { }
    private final SessionResumeController controller;
    private final Consumer<String> output;
    private Pending pending;

    TerminalSessionResumeUi(SessionResumeController controller, Consumer<String> output) {
        this.controller = controller;
        this.output = output;
    }
    boolean pending() { return pending != null; }
    void cancel() {
        pending = null;
        output.accept("已停止浏览，当前会话保持不变。");
    }
    void open(String target) {
        try {
            if (target == null || target.isBlank()) showPicker(0, "");
            else select("last".equalsIgnoreCase(target) ? controller.latest() : target);
        } catch (Exception e) { error(); }
    }
    void list() {
        try { display(controller.list(0, "")); }
        catch (Exception e) { error(); }
    }
    void accept(String text) {
        if (pending == null) return;
        String input = text == null ? "" : text.strip();
        if (input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("q") || input.equals("/cancel")) {
            cancel(); return;
        }
        try {
            if (pending instanceof Picker picker) {
                if (input.equalsIgnoreCase("n")) showPicker(picker.offset() + 20, picker.query());
                else if (input.equalsIgnoreCase("p")) showPicker(Math.max(0, picker.offset() - 20), picker.query());
                else if (input.startsWith("/")) output.accept("选择编号、n/p、? 搜索词或 cancel。");
                else {
                    try {
                        int index = Integer.parseInt(input) - 1;
                        if (index < 0 || index >= picker.entries().size()) throw new IllegalArgumentException();
                        select(picker.entries().get(index).summary().sessionId());
                    } catch (NumberFormatException e) {
                        showPicker(0, input.startsWith("?") ? input.substring(1).strip() : input);
                    }
                }
            } else if (pending instanceof History history) {
                if (input.equalsIgnoreCase("all") && history.cleared())
                    showHistory(history.id(), TranscriptCursor.start(), history.watermark(), true);
                else if ((input.equalsIgnoreCase("n") || input.isEmpty()) && history.next() != null)
                    showHistory(history.id(), history.next(), history.watermark(), history.includeCleared());
                else output.accept("n/Enter 下一页；all 主动查看 clear 前历史；cancel 结束。");
            }
        } catch (Exception e) { error(); }
    }
    private void showPicker(int offset, String query) throws Exception {
        var entries = controller.list(offset, query);
        pending = new Picker(offset, query, entries);
        output.accept("会话选择（第 " + (offset / 20 + 1) + " 页）");
        display(entries);
        output.accept("输入编号选择，n/p 翻页，? 搜索词，cancel 取消。");
    }
    private void display(List<SessionListEntry> entries) {
        if (entries.isEmpty()) output.accept("当前项目没有匹配会话。");
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            output.accept((i + 1) + ". " + e.title() + " · " + e.summary().sessionId()
                    + " · " + java.time.Instant.ofEpochMilli(e.summary().updatedAt())
                    + " · " + (e.attachable() ? "可恢复" : e.availability())
                    + (e.attachable() ? "" : "（" + e.unavailableReason() + "）")
                    + " · 等待 " + e.waitingExecutionCount()
                    + (e.hasUnfinishedPlan() ? " · 未完成 Plan" : ""));
        }
    }
    private void select(String id) throws Exception {
        var entry = controller.attach(id);
        pending = null;
        output.accept("已恢复会话 " + id + "；等待用户输入，旧任务保持等待。");
        if (entry.waitingExecutionCount() > 0) output.accept("已保存 " + entry.waitingExecutionCount() + " 条等待输入。");
        showHistory(id, TranscriptCursor.start(), -1, false);
    }
    private void showHistory(String id, TranscriptCursor cursor, long watermark, boolean includeCleared) throws Exception {
        var page = controller.history(id, cursor, watermark, includeCleared);
        for (var entry : page.entries()) output.accept("[" + entry.mode() + "/" + entry.role() + "] " + entry.text());
        if (page.entries().isEmpty()) output.accept("暂无已执行的顶层对话记录；保存的等待输入未执行。");
        if (page.nextCursor() != null || (page.hasClearedHistory() && !includeCleared)) {
            pending = new History(id, page.nextCursor(), page.snapshotLastSequence(), page.hasClearedHistory(), includeCleared);
            output.accept("n/Enter 下一页；all 主动查看 clear 前历史；cancel 结束浏览（保留已选会话）。");
        } else { pending = null; output.accept("历史已显示完毕，等待用户输入。"); }
    }
    private void error() { output.accept("会话操作失败：会话不可用、正在执行或输入无效；未启动任务。"); }
}
