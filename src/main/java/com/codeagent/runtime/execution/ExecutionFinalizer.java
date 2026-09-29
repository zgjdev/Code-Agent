package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.history.SessionEvent;
import com.codeagent.history.SessionEventDraft;
import com.codeagent.history.SessionProjection;
import com.codeagent.history.SessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class ExecutionFinalizer {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RuntimeExecutionStore store;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public ExecutionFinalizer(RuntimeExecutionStore store) {
        this.store = store;
    }

    public void ensureStarted(RuntimeExecution execution, SessionStore.SessionHandle session)
            throws IOException {
        Object lock = locks.computeIfAbsent(execution.id(), ignored -> new Object());
        synchronized (lock) {
            SessionProjection.ExecutionEnvelope existing =
                    session.projection().executionEnvelopes().get(execution.id());
            if (existing != null) {
                if (!execution.ordinal().equals(existing.ordinal())) {
                    throw new IllegalStateException(
                            "Execution envelope ordinal conflict: " + execution.id());
                }
                return;
            }
            ObjectNode payload = JSON.createObjectNode()
                    .put("executionId", execution.id())
                    .put("ordinal", execution.ordinal());
            if (execution.explicitMode() != null) {
                payload.put("explicitMode", lower(execution.explicitMode()));
            }
            session.append(new SessionEventDraft(
                    SessionEvent.Types.EXECUTION_START,
                    execution.selectedMode() == null ? "runtime" : lower(execution.selectedMode()),
                    "execution-coordinator",
                    "runtime-execution",
                    false,
                    SessionEvent.SurfaceOperation.none(),
                    payload));
        }
    }

    public RuntimeExecution finish(
            String executionId,
            TopLevelExecutionResult proposed,
            SessionStore.SessionHandle session) throws SQLException, IOException {
        Object lock = locks.computeIfAbsent(executionId, ignored -> new Object());
        synchronized (lock) {
            RuntimeExecution current = store.find(executionId)
                    .orElseThrow(() -> new IllegalStateException("Unknown execution: " + executionId));
            if (current.status() == ExecutionStatus.RUNNING
                    && session.projection().executionEnvelopes().get(executionId) == null) {
                ensureStarted(current, session);
            }

            SessionProjection.ExecutionEnvelope envelope =
                    session.projection().executionEnvelopes().get(executionId);
            if (current.terminal()) {
                ensureTerminalEnvelope(current, envelope, session);
                locks.remove(executionId, lock);
                return current;
            }
            if (current.status() != ExecutionStatus.RUNNING) {
                throw new IllegalStateException("Execution is not running: " + executionId);
            }

            ExecutionOutcome outcome;
            String result;
            String error;
            if (envelope != null && envelope.ended()) {
                outcome = ExecutionOutcome.fromDatabase(envelope.outcome());
                result = proposed == null ? null : proposed.result();
                error = proposed == null ? null : proposed.error();
                verifyEnvelope(envelope, current.selectedMode(), outcome, statusFor(outcome));
            } else {
                if (proposed == null) {
                    throw new IllegalArgumentException("proposed result must not be null");
                }
                outcome = current.cancelRequestedAt() == null
                        ? proposed.outcome() : ExecutionOutcome.CANCELED;
                result = proposed.result();
                error = proposed.error();
                appendEnd(current, outcome, statusFor(outcome), session);
            }
            RuntimeExecution terminal = store.complete(executionId, outcome, result, error);
            SessionProjection.ExecutionEnvelope committed =
                    session.projection().executionEnvelopes().get(executionId);
            verifyEnvelope(committed, terminal.selectedMode(), terminal.outcome(), terminal.status());
            locks.remove(executionId, lock);
            return terminal;
        }
    }

    private void ensureTerminalEnvelope(
            RuntimeExecution execution,
            SessionProjection.ExecutionEnvelope envelope,
            SessionStore.SessionHandle session) throws IOException {
        if (envelope == null) {
            throw new IllegalStateException("Terminal execution has no start envelope: " + execution.id());
        }
        if (!envelope.ended()) {
            appendEnd(execution, execution.outcome(), execution.status(), session);
            return;
        }
        verifyEnvelope(envelope, execution.selectedMode(), execution.outcome(), execution.status());
    }

    private void appendEnd(
            RuntimeExecution execution,
            ExecutionOutcome outcome,
            ExecutionStatus status,
            SessionStore.SessionHandle session) throws IOException {
        if (execution.selectedMode() == null || outcome == null) {
            throw new IllegalStateException("Execution terminal envelope is incomplete: " + execution.id());
        }
        ObjectNode payload = JSON.createObjectNode()
                .put("executionId", execution.id())
                .put("selectedMode", lower(execution.selectedMode()))
                .put("outcome", outcome.databaseValue())
                .put("status", status.databaseValue());
        session.append(new SessionEventDraft(
                SessionEvent.Types.EXECUTION_END,
                lower(execution.selectedMode()),
                "execution-finalizer",
                "runtime-execution",
                false,
                SessionEvent.SurfaceOperation.none(),
                payload));
    }

    private static void verifyEnvelope(
            SessionProjection.ExecutionEnvelope envelope,
            ExecutionMode mode,
            ExecutionOutcome outcome,
            ExecutionStatus status) {
        if (envelope == null || !envelope.ended()
                || mode == null || outcome == null || status == null
                || !lower(mode).equals(envelope.selectedMode())
                || !outcome.databaseValue().equals(envelope.outcome())
                || !status.databaseValue().equals(envelope.status())) {
            throw new IllegalStateException("Execution terminal state conflicts with session envelope");
        }
    }

    private static ExecutionStatus statusFor(ExecutionOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED, PARTIAL -> ExecutionStatus.COMPLETED;
            case FAILED, REJECTED -> ExecutionStatus.FAILED;
            case CANCELED -> ExecutionStatus.CANCELED;
        };
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
