package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.runtime.interaction.InteractionRequest;
import java.nio.file.Path;
import java.util.*;

/** Trusted Java control plane. Every workspace must be explicitly registered before accepting work. */
public final class RuntimeExecutionHost implements AutoCloseable {
    private static final Set<RuntimeExecutionHost> QUARANTINED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final RuntimeExecutionStore store;
    private final Path locksDirectory;
    private final ExecutionEventSink events;
    private final WorkspaceAwareExecutionScheduler scheduler;
    private final String ownerId = "owner_" + UUID.randomUUID().toString().replace("-", "");
    private final LinkedHashMap<WorkspaceKey, Registration> registrations = new LinkedHashMap<>();
    private final Set<WorkspaceKey> unregistering = new HashSet<>();
    private boolean closed;

    public RuntimeExecutionHost(RuntimeExecutionStore store, RuntimeExecutionOptions options, ExecutionEventSink sink) {
        this(store, options, sink, WorkspaceAwareExecutionScheduler.defaultLocks());
    }
    public RuntimeExecutionHost(RuntimeExecutionStore store, RuntimeExecutionOptions options, ExecutionEventSink sink, Path locks) {
        this.store = Objects.requireNonNull(store);
        this.events = Objects.requireNonNull(sink);
        this.locksDirectory = Objects.requireNonNull(locks);
        this.scheduler = new WorkspaceAwareExecutionScheduler(store, options, locks);
    }
    public synchronized WorkspaceIdentity registerWorkspace(Path root, WorkspaceRuntimeFactory factory) throws Exception {
        return registerWorkspace(root, factory, RecoveryPolicy.AUTOMATIC);
    }
    public synchronized WorkspaceIdentity registerWorkspace(Path root, WorkspaceRuntimeFactory factory, RecoveryPolicy policy) throws Exception {
        requireOpen();
        Objects.requireNonNull(policy,"policy");
        WorkspaceIdentity identity = new WorkspaceIdentityResolver().resolve(root);
        Registration existing = registrations.get(identity.key());
        if (existing != null) {
            if (unregistering.contains(identity.key())) throw new IllegalStateException("Workspace is unregistering; retry unregistration");
            if (existing.factory != factory) throw new IllegalStateException("Workspace already registered with a different factory");
            existing.owner.verifyIdentity();
            return existing.runtime.identity();
        }
        WorkspaceOwnerLease owner = WorkspaceOwnerLease.acquire(locksDirectory, identity, ownerId);
        WorkspaceRuntime runtime = null;
        try {
            runtime = factory.create(identity, events);
            if (!identity.equals(runtime.identity())) throw new IllegalStateException("Factory workspace identity mismatch");
            store.registerWorkspace(owner, root);
            runtime.reconcile(store, owner);
            if(policy==RecoveryPolicy.WAIT_FOR_USER) store.freezeForUser(owner);
            else store.recoverStaleRunning(owner);
            runtime.bindContinuationCapability(new ContinuationCapability(store, owner, runtime, scheduler::signalWork));
            scheduler.register(runtime, owner);
            registrations.put(identity.key(), new Registration(runtime, owner, factory));
            return identity;
        } catch (Exception | Error failure) {
            if (runtime != null) try { runtime.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
            try { owner.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
    public synchronized void start() throws java.sql.SQLException { requireOpen(); scheduler.start(); }
    public WorkspaceAwareExecutionScheduler scheduler() { return scheduler; }
    public synchronized String createSession(WorkspaceKey workspace) throws Exception {
        Registration registration = requireRegistration(workspace);
        if (!registration.runtime.mutationGate().tryLock()) throw new IllegalStateException("Workspace is executing");
        try {
            String id = registration.runtime.createSession();
            store.bindSession(registration.runtime.identity(), id);
            return id;
        } finally { registration.runtime.mutationGate().unlock(); }
    }
    public synchronized void attachSession(WorkspaceKey workspace, String id) throws Exception {
        Registration registration = requireRegistration(workspace);
        if (!registration.runtime.mutationGate().tryLock()) throw new IllegalStateException("Workspace is executing");
        try {
            registration.runtime.attachSession(id); // SessionStore metadata must match the real workspace.
            store.bindSession(registration.runtime.identity(), id);
        } finally { registration.runtime.mutationGate().unlock(); }
    }
    public synchronized RuntimeExecution submit(String sessionId, String input, ExecutionMode override) throws Exception {
        requireOpen();
        WorkspaceKey workspace = store.sessionWorkspace(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session is not attached to this host: " + sessionId));
        Registration registration = requireRegistration(workspace);
        registration.owner.verifyIdentity();
        RuntimeExecution execution = store.enqueue(registration.runtime.identity().canonicalRoot(), sessionId, input, override);
        scheduler.signalWork();
        return execution;
    }
    public synchronized CancelRequestResult cancel(String id) throws Exception {
        RuntimeExecution execution=requireExecution(id);
        CancelRequestResult result=scheduler.cancel(id);
        RuntimeExecution canceled=store.find(id).orElseThrow();
        if(canceled.terminal()&&canceled.selectedMode()!=null) {
            Registration registration=requireRegistration(execution.workspaceKey());
            if(registration.runtime.mutationGate().tryLock()) {
                try(var lease=registration.runtime.contexts().acquire(canceled.sessionId())) {
                    new ExecutionFinalizer(store).reconcileTerminal(registration.owner,canceled,lease.context().session());
                } finally { registration.runtime.mutationGate().unlock(); }
            }
        }
        return result;
    }
    public synchronized List<RuntimeExecution> list(WorkspaceKey workspace, int limit) throws Exception {
        return store.list(requireRegistration(workspace).runtime.identity().canonicalRoot(), limit);
    }
    public synchronized boolean respond(WorkspaceKey workspace, String executionId, String interactionId,
                                         String action, String text) throws Exception {
        Registration registration = requireRegistration(workspace);
        RuntimeExecution execution = requireExecution(executionId);
        if (!workspace.equals(execution.workspaceKey()) || execution.status() != ExecutionStatus.RUNNING
                || !registration.owner.ownerId().equals(execution.ownerId()) || execution.cancelRequestedAt() != null) return false;
        return registration.runtime.broker().respond(executionId, interactionId, action, text);
    }
    public synchronized Optional<InteractionRequest> pending(WorkspaceKey workspace) {
        return requireRegistration(workspace).runtime.broker().pending();
    }
    public synchronized void unregisterWorkspace(WorkspaceKey key) throws Exception {
        requireOpen();
        Registration registration = registrations.get(key);
        if (registration == null) throw new IllegalStateException("Workspace is not registered");
        if (store.workspaceExecutions(key).stream().anyMatch(e -> !e.terminal())) {
            throw new IllegalStateException("Workspace has queued or running executions");
        }
        scheduler.unregister(key);
        registration.runtime.stopContinuationAdmission();
        unregistering.add(key);
        registration.runtime.close();
        registration.owner.close();
        registrations.remove(key);
        unregistering.remove(key);
    }
    private Registration requireRegistration(WorkspaceKey key) {
        requireOpen();
        Registration value = registrations.get(key);
        if (value == null) throw new IllegalStateException("Workspace is not registered");
        if (unregistering.contains(key)) throw new IllegalStateException("Workspace is unregistering; retry unregistration");
        return value;
    }
    private RuntimeExecution requireExecution(String id) throws Exception {
        RuntimeExecution execution = store.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown execution"));
        requireRegistration(execution.workspaceKey());
        return execution;
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Execution host is closed"); }
    public synchronized boolean safelyStopped() { return scheduler.safelyStopped(); }
    @Override public void close() throws Exception {
        synchronized (this) {
            closed = true;
            registrations.values().forEach(registration -> registration.runtime.stopContinuationAdmission());
        }
        scheduler.close();
        Exception failure = null;
        synchronized (this) {
            var iterator = registrations.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (scheduler.isQuarantined(entry.getKey())) { QUARANTINED.add(this); continue; }
                try {
                    entry.getValue().runtime.close();
                    entry.getValue().owner.close();
                    iterator.remove();
                } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (registrations.isEmpty()) QUARANTINED.remove(this);
            else QUARANTINED.add(this);
        }
        if (failure != null) throw failure;
    }
    private record Registration(WorkspaceRuntime runtime, WorkspaceOwnerLease owner, WorkspaceRuntimeFactory factory) { }
}
