package com.codeagent.runtime.execution;

import com.codeagent.render.Renderer;
import com.codeagent.render.StatusInfo;
import com.codeagent.hitl.ApprovalRequest;
import com.codeagent.hitl.ApprovalResult;
import com.codeagent.llm.LlmClient;
import com.codeagent.runtime.interaction.ExecutionInteractionContext;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** All production worker output is emitted with its durable execution identity. */
final class ExecutionEventRenderer implements Renderer {
    private final WorkspaceIdentity identity;
    private final ExecutionEventSink sink;
    private final PrintStream output;
    ExecutionEventRenderer(WorkspaceIdentity identity, ExecutionEventSink sink) {
        this.identity = identity; this.sink = sink;
        output = new PrintStream(new ByteArrayOutputStream() {
            @Override public synchronized void flush() {
                if (size() == 0) return;
                String text = toString(StandardCharsets.UTF_8); reset(); emit("output", text);
            }
        }, true, StandardCharsets.UTF_8);
    }
    private void emit(String kind, Object payload) {
        var current = ExecutionInteractionContext.current();
        if (current != null) sink.accept(new RuntimeExecutionEvent(identity.key(), current.sessionId(), current.executionId(), kind, payload));
    }
    public void start() { }
    public void close() { output.close(); }
    public PrintStream stream() { return output; }
    public void appendToolCalls(List<LlmClient.ToolCall> calls) { emit("tool_calls", calls); }
    public void appendDiff(String path, String before, String after) { emit("diff", new String[]{path, before, after}); }
    public void updateStatus(StatusInfo status) { emit("status", status); }
    public ApprovalResult promptApproval(ApprovalRequest request) { return ApprovalResult.reject("Host interaction adapter required"); }
    public int openPalette(String title, List<String> items) { return -1; }
}
