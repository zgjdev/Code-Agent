package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** The single submission/control API used by ordinary input and /task compatibility commands. */
public final class RuntimeExecutionQueue {
    private final RuntimeExecutionStore store;
    private final WorkspaceAwareExecutionScheduler scheduler;
    private final Path workspace;

    public RuntimeExecutionQueue(
            RuntimeExecutionStore store,
            WorkspaceAwareExecutionScheduler scheduler,
            Path workspace) {
        this.store = store;
        this.scheduler = scheduler;
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    public RuntimeExecution submit(
            String sessionId, String submittedInput, ExecutionMode explicitMode) throws SQLException {
        RuntimeExecution execution = store.enqueue(
                workspace, sessionId, submittedInput, explicitMode);
        scheduler.signalWork();
        return execution;
    }

    public RuntimeExecution adoptLegacyPlan(
            String executionId, String sessionId, String submittedInput) throws SQLException {
        RuntimeExecution execution = store.adoptPlanExecution(
                executionId, workspace, sessionId, submittedInput);
        scheduler.signalWork();
        return execution;
    }

    public List<RuntimeExecution> list(int limit) throws SQLException {
        return store.list(workspace, limit);
    }

    public Optional<RuntimeExecution> find(String executionId) throws SQLException {
        return store.find(executionId);
    }

    public CancelRequestResult cancel(String executionId) throws SQLException {
        return scheduler.cancel(executionId);
    }

    public Optional<String> running() throws SQLException {
        return store.running(workspace);
    }

    public boolean hasNonTerminal(String sessionId) throws SQLException {
        return store.hasNonTerminal(sessionId);
    }
}
