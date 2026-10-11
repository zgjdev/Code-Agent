package com.codeagent.runtime.execution;

import com.codeagent.history.SessionStore;
import com.codeagent.runtime.CancellationToken;
import com.codeagent.runtime.interaction.InteractionBroker;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Complete workspace-local mutable dependencies. Host owns its slot and OS ownership lifetime. */
public final class WorkspaceRuntime implements AutoCloseable {
    private final WorkspaceIdentity identity;
    private final SessionExecutionContextRegistry<SessionExecutionContext> contexts;
    private final InteractionBroker broker;
    private final ReentrantLock mutationGate;
    private final Set<String> sharedResources;
    private final BooleanSupplier claimAllowed;
    private final SessionController sessions;
    private final Runner runner;
    private final AutoCloseable resources;
    private Runnable afterExecution = () -> { };
    private Runnable beforeClose = () -> { };

    public WorkspaceRuntime(WorkspaceIdentity identity,
            SessionExecutionContextRegistry<SessionExecutionContext> contexts,
            InteractionBroker broker, ReentrantLock mutationGate, Set<String> sharedResources,
            BooleanSupplier claimAllowed, SessionController sessions, Runner runner, AutoCloseable resources) {
        this.identity = Objects.requireNonNull(identity);
        this.contexts = Objects.requireNonNull(contexts);
        this.broker = Objects.requireNonNull(broker);
        this.mutationGate = Objects.requireNonNull(mutationGate);
        this.sharedResources = Set.copyOf(sharedResources);
        this.claimAllowed = Objects.requireNonNull(claimAllowed);
        this.sessions = Objects.requireNonNull(sessions);
        this.runner = Objects.requireNonNull(runner);
        this.resources = Objects.requireNonNull(resources);
    }
    public WorkspaceIdentity identity() { return identity; }
    public SessionExecutionContextRegistry<SessionExecutionContext> contexts() { return contexts; }
    public InteractionBroker broker() { return broker; }
    public ReentrantLock mutationGate() { return mutationGate; }
    public Set<String> sharedResourceKeys() { return sharedResources; }
    public boolean claimAllowed() { return claimAllowed.getAsBoolean(); }
    public void setAfterExecution(Runnable callback) { afterExecution = Objects.requireNonNull(callback); }
    public void setBeforeClose(Runnable callback) { beforeClose = Objects.requireNonNull(callback); }
    void afterExecution() { afterExecution.run(); }
    public String createSession() throws Exception { return sessions.create(); }
    public void attachSession(String id) throws Exception { sessions.attach(id); }
    public TopLevelExecutionResult run(RuntimeExecution execution, CancellationToken token,
                                       SessionExecutionContext context) throws Exception {
        context.activateSharedBindings();
        return runner.run(execution, token, context);
    }
    public void reconcile(RuntimeExecutionStore store, WorkspaceOwnerLease owner) throws Exception {
        mutationGate.lockInterruptibly();
        try {
            for (RuntimeExecution execution : store.workspaceExecutions(identity.key())) {
                // Queued requests without a start boundary require no writable context during startup.
                if (execution.status() == ExecutionStatus.ENQUEUED) continue;
                try (var lease = contexts.acquire(execution.sessionId())) {
                    SessionStore.SessionHandle session = lease.context().session();
                    if (execution.terminal()) {
                        new ExecutionFinalizer(store).reconcileTerminal(owner, execution, session);
                        continue;
                    }
                    var envelope = session.projection().executionEnvelopes().get(execution.id());
                    if (envelope == null || !envelope.ended()) continue;
                    RuntimeExecution recovered = store.claimForReconciliation(owner, execution.id());
                    if (recovered.selectedMode() == null) {
                        recovered = store.commitRoutingDecision(recovered.claim(),
                                com.codeagent.agent.ExecutionMode.valueOf(envelope.selectedMode().toUpperCase(java.util.Locale.ROOT)),
                                recovered.explicitMode() == null ? com.codeagent.agent.RoutingSource.AUTO_FALLBACK
                                        : com.codeagent.agent.RoutingSource.EXPLICIT);
                    }
                    // The durable end envelope is authoritative in the cross-store crash window.
                    store.completeReconciliation(owner, recovered.claim(), ExecutionOutcome.fromDatabase(envelope.outcome()),
                            recovered.result(), recovered.error());
                }
            }
        } finally { mutationGate.unlock(); }
    }
    @Override public void close() throws Exception {
        broker.close();
        beforeClose.run();
        contexts.close();
        resources.close();
    }
    public interface SessionController {
        String create() throws Exception;
        void attach(String sessionId) throws Exception;
    }
    @FunctionalInterface public interface Runner {
        TopLevelExecutionResult run(RuntimeExecution execution, CancellationToken token,
                                     SessionExecutionContext context) throws Exception;
    }
}
