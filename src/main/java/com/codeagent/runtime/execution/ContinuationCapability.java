package com.codeagent.runtime.execution;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host-prebound authority. No worker callback takes the Host monitor or exposes its owner lease. */
public final class ContinuationCapability {
    private final RuntimeExecutionStore store;
    private final WorkspaceOwnerLease owner;
    private final WorkspaceRuntime runtime;
    private final Runnable signal;
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    ContinuationCapability(RuntimeExecutionStore store, WorkspaceOwnerLease owner, WorkspaceRuntime runtime, Runnable signal) {
        this.store = store; this.owner = owner; this.runtime = runtime; this.signal = signal;
    }

    public Scope requireRunningScope(ExecutionClaim claim, SessionExecutionContext context) throws Exception {
        if (!enabled.get()) throw new IllegalStateException("Continuation admission is closed");
        requireGate();
        var proof = runtime.contexts().attest(context.sessionId(), context);
        var scope = new Scope(this, claim, context, proof);
        requireRunning(scope);
        return scope;
    }

    RuntimeExecution requireRunning(Scope scope) throws Exception {
        RuntimeExecution current = verify(scope);
        if (current.status() != ExecutionStatus.RUNNING || current.cancelRequestedAt() != null)
            throw new IllegalStateException("Continuation requires an active uncanceled claim");
        return current;
    }

    RuntimeExecution verify(Scope scope) throws Exception {
        if (scope == null || scope.capability != this) throw new IllegalStateException("Foreign admission scope");
        requireGate();
        runtime.contexts().requireStillCurrent(scope.lease, scope.context.sessionId(), scope.context);
        owner.verifyIdentity();
        RuntimeExecution current = store.find(scope.claim.executionId()).orElseThrow();
        if (!scope.claim.equals(current.claim()) || !owner.ownerId().equals(scope.claim.ownerId())
                || !runtime.identity().key().equals(current.workspaceKey())
                || !current.sessionId().equals(scope.context.sessionId())
                || !runtime.identity().canonicalRoot().toString().equals(scope.context.session().manifest().workspace()))
            throw new IllegalStateException("Admission claim scope changed");
        return current;
    }

    RuntimeExecution prepare(Scope scope, String targetId, int generation) throws Exception {
        requireRunning(scope);
        if (!enabled.get()) throw new IllegalStateException("Continuation admission is closed");
        return store.prepareContinuation(scope.claim, targetId, generation);
    }

    public ContinuationReleaseResult releaseCompleted(Scope scope) throws Exception {
        RuntimeExecution request = verify(scope);
        var envelope = scope.context.session().projection().executionEnvelopes().get(request.id());
        if (!enabled.get()) return ContinuationReleaseResult.STALE;
        if (request.requestSemantics() != RequestSemantics.CONTINUE || request.controlTargetGeneration() == null
                || request.status() != ExecutionStatus.COMPLETED || request.outcome() != ExecutionOutcome.SUCCEEDED
                || envelope == null || !envelope.ended() || envelope.userSequence() == null || envelope.assistantSequence() == null
                || !"react".equals(envelope.selectedMode()) || !"succeeded".equals(envelope.outcome()) || !"completed".equals(envelope.status()))
            return ContinuationReleaseResult.NOT_READY;
        var result = store.releaseContinuation(owner, request.id(), request.controlTargetExecutionId(), request.controlTargetGeneration());
        if (result == ContinuationReleaseResult.RELEASED) signal.run();
        return result;
    }

    public void verifyRecoveryBoundary(Scope scope, WorkspaceIdentity identity, String sessionId, ExecutionClaim claim) throws java.io.IOException {
        try {
            requireRunning(scope);
            if (!runtime.identity().equals(identity) || !scope.context.sessionId().equals(sessionId) || !scope.claim.equals(claim))
                throw new IllegalStateException("Recovery boundary differs from physical lease");
        } catch (Exception failure) { throw new java.io.IOException("Recovery lease verification failed", failure); }
    }

    public RuntimeExecution deferBeforeEffects(Scope scope, RecoveryPreparationDeferredException failure) throws Exception {
        requireRunning(scope);
        Objects.requireNonNull(failure);
        return store.deferForUser(scope.claim, failure.getMessage());
    }

    void disable() { enabled.set(false); }
    private void requireGate() {
        if (!runtime.mutationGate().isHeldByCurrentThread()) throw new IllegalStateException("Continuation requires mutation gate");
    }

    public static final class Scope {
        private final ContinuationCapability capability;
        private final ExecutionClaim claim;
        private final SessionExecutionContext context;
        private final SessionExecutionContextRegistry.LeaseAttestation lease;
        private Scope(ContinuationCapability capability, ExecutionClaim claim, SessionExecutionContext context,
                      SessionExecutionContextRegistry.LeaseAttestation lease) {
            this.capability = capability; this.claim = claim; this.context = context; this.lease = lease;
        }
        public ExecutionClaim claim() { return claim; }
        SessionExecutionContext context() { return context; }
    }
}
