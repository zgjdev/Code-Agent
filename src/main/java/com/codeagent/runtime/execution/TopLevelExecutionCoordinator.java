package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.RoutingDecision;
import com.codeagent.history.SessionProjection;
import com.codeagent.runtime.CancellationToken;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

public final class TopLevelExecutionCoordinator implements TopLevelExecutionRunner {
    private final RuntimeExecutionStore store;
    private final ExecutionInputResolver inputResolver;
    private final ModeSelector modeSelector;
    private final ConversationHistoryProvider historyProvider;
    private final SnapshotBoundary snapshotBoundary;
    private final ModeExecutionAdapter reactAdapter;
    private final ModeExecutionAdapter planAdapter;

    public TopLevelExecutionCoordinator(
            RuntimeExecutionStore store,
            ExecutionInputResolver inputResolver,
            ModeSelector modeSelector,
            ConversationHistoryProvider historyProvider,
            SnapshotBoundary snapshotBoundary,
            ModeExecutionAdapter reactAdapter,
            ModeExecutionAdapter planAdapter) {
        this.store = Objects.requireNonNull(store, "store");
        this.inputResolver = Objects.requireNonNull(inputResolver, "inputResolver");
        this.modeSelector = Objects.requireNonNull(modeSelector, "modeSelector");
        this.historyProvider = Objects.requireNonNull(historyProvider, "historyProvider");
        this.snapshotBoundary = Objects.requireNonNull(snapshotBoundary, "snapshotBoundary");
        this.reactAdapter = Objects.requireNonNull(reactAdapter, "reactAdapter");
        this.planAdapter = Objects.requireNonNull(planAdapter, "planAdapter");
    }

    @Override
    public TopLevelExecutionResult run(RuntimeExecution execution, CancellationToken token) throws Exception {
        RuntimeExecution current = requireRunning(execution.id());
        ensureNotCancelled(token);

        if (current.resolvedTaskInput() == null) {
            String resolved = inputResolver.resolve(current);
            current = store.commitResolvedInput(current.id(), resolved);
        }

        RoutingDecision decision = routingDecision(current);
        current = store.commitRoutingDecision(current.id(), decision.mode(), decision.source());
        ensureNotCancelled(token);

        RuntimeExecution durable = current;
        ModeExecutionAdapter adapter = decision.mode() == ExecutionMode.PLAN ? planAdapter : reactAdapter;
        TopLevelExecutionResult result = snapshotBoundary.run(
                decision.mode(),
                durable.submittedInput(),
                () -> adapter.execute(durable, durable.resolvedTaskInput(), token));
        if (result == null) {
            throw new IllegalStateException("Mode adapter returned no execution result");
        }
        return new TopLevelExecutionResult(
                decision.mode(), decision.source(), result.outcome(), result.result(), result.error());
    }

    private RoutingDecision routingDecision(RuntimeExecution execution) throws Exception {
        if (execution.selectedMode() != null) {
            if (execution.routingSource() == null) {
                throw new IllegalStateException("Persisted mode has no routing source: " + execution.id());
            }
            return new RoutingDecision(execution.selectedMode(), execution.routingSource(), null);
        }
        if (execution.explicitMode() != null) {
            return RoutingDecision.explicit(execution.explicitMode());
        }
        List<SessionProjection.ConversationNode> history = historyProvider.load(execution.sessionId());
        return modeSelector.route(execution.submittedInput(), history == null ? List.of() : history);
    }

    private RuntimeExecution requireRunning(String id) throws SQLException {
        RuntimeExecution current = store.find(id)
                .orElseThrow(() -> new IllegalStateException("Unknown execution: " + id));
        if (current.status() != ExecutionStatus.RUNNING) {
            throw new IllegalStateException("Coordinator requires a running execution: " + id);
        }
        return current;
    }

    private static void ensureNotCancelled(CancellationToken token) {
        if (token != null && token.isCancelled()) {
            throw new CancellationException("Execution canceled");
        }
    }

    @FunctionalInterface
    public interface ExecutionInputResolver {
        String resolve(RuntimeExecution execution) throws Exception;
    }

    @FunctionalInterface
    public interface ModeSelector {
        RoutingDecision route(
                String submittedInput, List<SessionProjection.ConversationNode> history) throws Exception;
    }

    @FunctionalInterface
    public interface ConversationHistoryProvider {
        List<SessionProjection.ConversationNode> load(String sessionId) throws Exception;
    }

    @FunctionalInterface
    public interface SnapshotBoundary {
        TopLevelExecutionResult run(
                ExecutionMode mode, String submittedInput, ExecutionAction action) throws Exception;
    }

    @FunctionalInterface
    public interface ExecutionAction {
        TopLevelExecutionResult run() throws Exception;
    }

    @FunctionalInterface
    public interface ModeExecutionAdapter {
        TopLevelExecutionResult execute(
                RuntimeExecution execution, String resolvedTaskInput, CancellationToken token) throws Exception;
    }
}
