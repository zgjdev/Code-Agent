package com.codeagent.plan;

import com.codeagent.history.*;
import com.codeagent.util.RecoveryIntegrity;
import com.codeagent.model.recovery.*;
import com.codeagent.tool.*;
import com.codeagent.tool.ToolRegistry.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.*;

/**
 * Child force precedes typed DB references. The small write lock never spans a tool effect.
 */
public final class TaskRecoveryObserver implements TaskToolObserver {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TaskRecoveryStore store;

    private final SessionStore.SessionHandle child;

    private final TaskAttemptBinding binding;

    private final Map<String, Long> starts = new HashMap<>();

    public TaskRecoveryObserver(TaskRecoveryStore store, SessionStore.SessionHandle child, TaskAttemptBinding binding) {
        this.store = Objects.requireNonNull(store);
        this.child = Objects.requireNonNull(child);
        this.binding = Objects.requireNonNull(binding);
    }

    private String key(TaskObservationContext c, ToolInvocation i) {
        return c.batchId() + "/" + i.id();
    }

    private ObjectNode payload(TaskObservationContext c, ToolInvocation i) throws Exception {
        if (!binding.equals(c.binding()) || !child.sessionId().equals(binding.childSessionId()))
            throw new IOException("Task observation identity mismatch");
        store.requireExact(binding);
        return JSON.createObjectNode().put("invocationId", i.id()).put("attemptId", binding.attemptId()).put("batchId", c.batchId()).put("callOrdinal", c.callOrdinal()).put("callKey", key(c, i)).put("name", i.name());
    }

    @Override
    public synchronized void started(TaskObservationContext c, ToolInvocation i) throws Exception {
        var payload = payload(c, i).put("argumentsHash", RecoveryIntegrity.hash(Objects.toString(i.argumentsJson(), "")));
        var event = child.append(new SessionEventDraft(SessionEvent.Types.TOOL_EXECUTION_STARTED, "plan", child.manifest().actor(), "task_recovery_typed_start", false, SessionEvent.SurfaceOperation.none(), payload));
        store.recordStarted(binding, key(c, i), c.callOrdinal(), i.name(), event.sequence(), RecoveryIntegrity.hash(payload.toString()));
        starts.put(key(c, i), event.sequence());
    }

    @Override
    public synchronized void completed(TaskObservationContext c, ToolInvocation i, ToolExecutionResult result) throws Exception {
        Long start = starts.get(key(c, i));
        if (start == null)
            throw new IOException("Task observation start unavailable");
        var payload = payload(c, i).put("startSequence", start).put("successful", result.successful()).put("timedOut", result.timedOut()).put("outputHash", RecoveryIntegrity.hash(Objects.toString(result.result(), "")));
        if (result.exitCode() != null)
            payload.put("exitCode", result.exitCode());
        var event = child.append(new SessionEventDraft(SessionEvent.Types.TOOL_RESULT, "plan", child.manifest().actor(), "task_recovery_typed_result", false, SessionEvent.SurfaceOperation.none(), payload));
        store.recordResult(binding, key(c, i), c.callOrdinal(), i.name(), result.successful(), result.timedOut(), result.exitCode(), event.sequence(), RecoveryIntegrity.hash(payload.toString()));
        store.checkpoint(binding, event.sequence(), "TOOL_RESULT");
    }

    public synchronized void checkpoint(String phase) throws Exception {
        store.checkpoint(binding, child.manifest().lastEventSequence(), phase);
    }
}
