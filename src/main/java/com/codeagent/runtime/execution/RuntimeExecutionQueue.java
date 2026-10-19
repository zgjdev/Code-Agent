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
    private final RuntimeExecutionHost host;

    public RuntimeExecutionQueue(
            RuntimeExecutionStore store,
            WorkspaceAwareExecutionScheduler scheduler,
            Path workspace) {
        this.store = store;
        this.scheduler = scheduler;
        this.workspace = workspace.toAbsolutePath().normalize();
        this.host = null;
    }

    public RuntimeExecutionQueue(RuntimeExecutionStore store, RuntimeExecutionHost host, WorkspaceIdentity workspace) {
        this.store = store; this.host = host; this.scheduler = host.scheduler(); this.workspace = workspace.canonicalRoot();
    }

    public RuntimeExecution submit(
            String sessionId, String submittedInput, ExecutionMode explicitMode) throws SQLException {
        if (host != null) {
            try {
                if (store.sessionWorkspace(sessionId).isEmpty()) {
                    host.attachSession(new WorkspaceIdentityResolver().resolve(workspace).key(), sessionId);
                }
                return host.submit(sessionId, submittedInput, explicitMode);
            } catch (SQLException failure) { throw failure; }
            catch (Exception failure) { throw new SQLException("Execution submission failed", failure); }
        }
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
        WorkspaceKey key;
        try { key = new WorkspaceIdentityResolver().resolve(workspace).key(); }
        catch (java.io.IOException failure) { throw new SQLException("Workspace is unavailable", failure); }
        return store.find(executionId).filter(execution -> key.equals(execution.workspaceKey()));
    }

    public CancelRequestResult cancel(String executionId) throws SQLException {
        if (find(executionId).isEmpty()) return CancelRequestResult.NOT_FOUND;
        if (host != null) {
            try { return host.cancel(executionId); }
            catch (SQLException failure) { throw failure; }
            catch (Exception failure) { throw new SQLException("Execution cancellation failed", failure); }
        }
        return scheduler.cancel(executionId);
    }

    public Optional<String> running() throws SQLException {
        return store.running(workspace);
    }

    public boolean hasNonTerminal(String sessionId) throws SQLException {
        return store.hasNonTerminal(sessionId);
    }
    public boolean hasActiveOrReadyExecution(String sessionId) throws SQLException {
        return store.hasActiveOrReadyExecution(sessionId);
    }
}
