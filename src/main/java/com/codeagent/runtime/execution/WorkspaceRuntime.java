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
    private volatile ContinuationCapability continuationCapability;

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
    synchronized void bindContinuationCapability(ContinuationCapability capability) {
        if (continuationCapability != null) throw new IllegalStateException("Continuation capability already bound");
        continuationCapability = Objects.requireNonNull(capability);
    }
    public ContinuationCapability continuationCapability() {
        var value = continuationCapability;
        if (value == null) throw new IllegalStateException("Runtime has no Host continuation capability");
        return value;
    }
    void stopContinuationAdmission() { if (continuationCapability != null) continuationCapability.disable(); }
    public void setAfterExecution(Runnable callback) { afterExecution = Objects.requireNonNull(callback); }
    public void setBeforeClose(Runnable callback) { beforeClose = Objects.requireNonNull(callback); }
    void afterExecution() { afterExecution.run(); }
    public String createSession() throws Exception { return sessions.create(); }
    public void attachSession(String id) throws Exception { sessions.attach(id); }
    public TopLevelExecutionResult run(RuntimeExecution execution, CancellationToken token,
                                       SessionExecutionContext context) throws Exception {
        context.activateSharedBindings();
        try { return runner.run(execution, token, context); }
        catch (RecoveryPreparationDeferredException deferred) {
            // The runner's typed contract includes physical writer/child drain. Preserve the
            // Session envelope and retain all ownership until the scheduler's finally exits.
            var capability = continuationCapability();
            capability.deferBeforeEffects(capability.requireRunningScope(execution.claim(), context), deferred);
            throw deferred;
        }
    }
    public void reconcile(RuntimeExecutionStore store, WorkspaceOwnerLease owner) throws Exception {
        mutationGate.lockInterruptibly();
        try {
            for (RuntimeExecution execution : store.workspaceExecutions(identity.key())) {
                // Never-started queued requests need no writable context. A prior attempt can already
                // have a durable END even if shutdown moved its database row back to enqueued.
                if (execution.status() == ExecutionStatus.ENQUEUED && execution.attempt() == 0) continue;
                try (var lease = contexts.acquire(execution.sessionId())) {
                    SessionStore.SessionHandle session = lease.context().session();
                    if (execution.terminal()) {
                        new ExecutionFinalizer(store).reconcileTerminal(owner, execution, session);
                        continue;
                    }
                    var envelope = session.projection().executionEnvelopes().get(execution.id());
                    if (envelope == null || !envelope.ended()) continue;
                    if (!session.sessionId().equals(execution.sessionId()))
                        throw new IllegalStateException("Recovery Session mismatch");
                    // Validate the whole durable END and fence the old claim in one transaction;
                    // another Session can still own the workspace's stale running slot here.
                    store.completeReconciliation(owner, execution, envelope);
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
