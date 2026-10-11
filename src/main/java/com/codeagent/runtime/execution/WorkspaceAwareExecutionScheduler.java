package com.codeagent.runtime.execution;

import com.codeagent.runtime.CancellationContext;
import com.codeagent.runtime.CancellationToken;
import com.codeagent.runtime.interaction.ExecutionInteractionContext;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One dispatcher, bounded worker capacity, and a retained ownership lifetime for every workspace. */
public final class WorkspaceAwareExecutionScheduler implements AutoCloseable {
    private final RuntimeExecutionStore store;
    private final RuntimeExecutionOptions options;
    private final Path locksDirectory;
    private final Semaphore capacity;
    private final SharedExecutionResourceLeases sharedLeases;
    private final LinkedHashMap<WorkspaceKey, Entry> workspaces = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, CancellationToken> tokens = new ConcurrentHashMap<>();
    private final Object wakeup = new Object();
    private final String ownerId = "owner_" + UUID.randomUUID().toString().replace("-", "");
    private final Path legacyWorkspace;
    private final TopLevelExecutionRunner legacyRunner;
    private final BooleanSupplier legacyGate;
    private volatile boolean running;
    private volatile boolean closed;
    private Thread dispatcher;
    private ThreadPoolExecutor workers;
    private int cursor;

    public WorkspaceAwareExecutionScheduler(RuntimeExecutionStore store, Path workspace, TopLevelExecutionRunner runner) {
        this(store, workspace, runner, () -> true);
    }
    public WorkspaceAwareExecutionScheduler(RuntimeExecutionStore store, Path workspace,
                                            TopLevelExecutionRunner runner, BooleanSupplier gate) {
        this(store, new RuntimeExecutionOptions(1), defaultLocks(), workspace, runner, gate);
    }
    public WorkspaceAwareExecutionScheduler(RuntimeExecutionStore store, RuntimeExecutionOptions options, Path locks) {
        this(store, options, locks, null, null, null);
    }
    private WorkspaceAwareExecutionScheduler(RuntimeExecutionStore store, RuntimeExecutionOptions options, Path locks,
            Path legacyWorkspace, TopLevelExecutionRunner legacyRunner, BooleanSupplier legacyGate) {
        this.store = Objects.requireNonNull(store);
        this.options = Objects.requireNonNull(options);
        this.locksDirectory = locks;
        this.capacity = new Semaphore(options.maxConcurrentExecutions());
        this.sharedLeases = new SharedExecutionResourceLeases(locks);
        this.legacyWorkspace = legacyWorkspace;
        this.legacyRunner = legacyRunner;
        this.legacyGate = legacyGate;
    }
    static Path defaultLocks() { return Path.of(System.getProperty("user.home"), ".codeagent", "runtime", "locks"); }

    synchronized void register(WorkspaceRuntime runtime, WorkspaceOwnerLease owner) {
        if (closed) throw new IllegalStateException("Execution scheduler is closed");
        if (workspaces.containsKey(runtime.identity().key())) throw new IllegalStateException("Workspace already registered");
        workspaces.put(runtime.identity().key(), new Entry(runtime.identity(), owner, runtime, null, runtime::claimAllowed));
        signalWork();
    }
    synchronized void unregister(WorkspaceKey key) {
        Entry entry = workspaces.get(key);
        if (entry == null) return;
        if (entry.slot.availablePermits() == 0) throw new IllegalStateException("Workspace is executing or quarantined");
        workspaces.remove(key);
    }
    public synchronized void start() throws SQLException {
        if (closed) throw new IllegalStateException("Execution scheduler cannot restart after shutdown");
        if (running) return;
        if (legacyWorkspace != null && workspaces.isEmpty()) {
            WorkspaceOwnerLease acquired = null;
            try {
                WorkspaceIdentity identity = new WorkspaceIdentityResolver().resolve(legacyWorkspace);
                acquired = WorkspaceOwnerLease.acquire(locksDirectory, identity, ownerId);
                store.registerWorkspace(acquired);
                store.recoverStaleRunning(acquired);
                workspaces.put(identity.key(), new Entry(identity, acquired, null, legacyRunner, legacyGate));
            } catch (Exception failure) {
                if (acquired != null) try { acquired.close(); } catch (Exception e) { failure.addSuppressed(e); }
                throw new SQLException("Cannot establish workspace execution ownership", failure);
            }
        }
        int size = options.maxConcurrentExecutions();
        workers = new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(size), task -> {
            Thread thread = new Thread(task, "codeagent-execution-worker"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        running = true;
        dispatcher = new Thread(this::dispatch, "codeagent-execution-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }
    public void signalWork() { synchronized (wakeup) { wakeup.notifyAll(); } }
    public CancelRequestResult cancel(String id) throws SQLException {
        RuntimeExecution current = store.find(id).orElse(null);
        if (current == null) return CancelRequestResult.NOT_FOUND;
        synchronized (this) {
            Entry entry = workspaces.get(current.workspaceKey());
            if (entry == null || !entry.owner.isValid()) return CancelRequestResult.NOT_FOUND;
            if (current.status() == ExecutionStatus.RUNNING && !entry.owner.ownerId().equals(current.ownerId())) {
                throw new IllegalStateException("Cannot cancel an execution owned by another runtime");
            }
        }
        CancelRequestResult result = store.requestCancel(id);
        CancellationToken token = tokens.get(id);
        if (token != null) token.cancel();
        synchronized (this) {
            workspaces.values().forEach(entry -> {
                if (entry.runtime != null) entry.runtime.broker().cancelExecution(id, "Execution canceled");
            });
        }
        signalWork();
        return result;
    }
    private void dispatch() {
        while (running) {
            List<Entry> entries;
            synchronized (this) { entries = new ArrayList<>(workspaces.values()); }
            boolean submitted = false;
            for (int offset = 0; running && offset < entries.size(); offset++) {
                Entry entry = entries.get((cursor + offset) % entries.size());
                if (!entry.active || !capacity.tryAcquire()) continue;
                if (!entry.slot.tryAcquire()) { capacity.release(); continue; }
                SharedExecutionResourceLeases.Lease resources = null;
                try {
                    if (!entry.gate.getAsBoolean()) { entry.slot.release(); capacity.release(); continue; }
                    var shared = sharedLeases.tryAcquire(entry.runtime == null ? Set.of() : entry.runtime.sharedResourceKeys());
                    if (shared.isEmpty()) { entry.slot.release(); capacity.release(); continue; }
                    resources = shared.orElseThrow();
                    RuntimeExecution candidate = store.nextQueuedExecution(entry.identity.key()).orElse(null);
                    if (candidate == null) { resources.close(); entry.slot.release(); capacity.release(); continue; }
                    Reservation task = new Reservation(entry, candidate, resources);
                    resources = null;
                    try { workers.execute(task); submitted = true; }
                    catch (RejectedExecutionException rejected) { task.discard(); }
                } catch (Exception failure) {
                    if (resources != null) try { resources.close(); } catch (Exception ignored) { }
                    entry.slot.release(); capacity.release();
                    entry.lastFailure = failure;
                }
            }
            if (!entries.isEmpty()) cursor = (cursor + 1) % entries.size();
            if (!submitted) {
                synchronized (wakeup) {
                    try { wakeup.wait(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
    }
    private final class Reservation implements Runnable {
        private final Entry entry;
        private final RuntimeExecution candidate;
        private final SharedExecutionResourceLeases.Lease resources;
        private final AtomicBoolean accepted = new AtomicBoolean();
        Reservation(Entry entry, RuntimeExecution candidate, SharedExecutionResourceLeases.Lease resources) {
            this.entry = entry; this.candidate = candidate; this.resources = resources;
        }
        public void discard() { if (accepted.compareAndSet(false, true)) release(); }
        private void release() {
            if (entry.quarantined) { entry.retainedResources = resources; return; }
            try { resources.close(); } catch (Exception e) { entry.lastFailure = e; entry.active = false; return; }
            entry.slot.release(); capacity.release(); signalWork();
        }
        @Override public void run() {
            if (!accepted.compareAndSet(false, true)) return;
            boolean gate = false;
            SessionExecutionContextRegistry.Lease<SessionExecutionContext> session = null;
            RuntimeExecution execution = null;
            CancellationToken token = new CancellationToken();
            try {
                if (!running || !entry.active) return;
                entry.owner.verifyIdentity();
                if (entry.runtime != null) {
                    gate = entry.runtime.mutationGate().tryLock();
                    if (!gate) return;
                    session = entry.runtime.contexts().acquire(candidate.sessionId());
                }
                if (!running || !entry.active || !entry.gate.getAsBoolean()) return;
                execution = store.claimCandidate(entry.owner, candidate.id()).orElse(null);
                if (execution == null) return;
                tokens.put(execution.id(), token);
                if (store.find(execution.id()).orElseThrow().cancelRequestedAt() != null) token.cancel();
                try (var cancellation = CancellationContext.install(token);
                     var interaction = ExecutionInteractionContext.install(execution.id(), execution.sessionId())) {
                    TopLevelExecutionResult result = entry.runtime == null ? entry.runner.run(execution, token)
                            : entry.runtime.run(execution, token, session.context());
                    RuntimeExecution current = store.find(execution.id()).orElseThrow();
                    if (!current.terminal()) {
                        if (!running) store.interruptForRecovery(execution.claim(), "runtime_shutdown");
                        else if (entry.runtime == null) store.complete(execution.claim(), result.outcome(), result.result(), result.error());
                        else throw new IllegalStateException("Production runtime did not finalize its execution");
                    }
                }
            } catch (Exception failure) {
                entry.lastFailure = failure;
                if (execution != null) {
                    try {
                        if (!running) store.interruptForRecovery(execution.claim(), "runtime_shutdown");
                        else if (entry.runtime == null) store.complete(execution.claim(), token.isCancelled() ? ExecutionOutcome.CANCELED
                                : ExecutionOutcome.FAILED, null, stableMessage(failure));
                        else {
                            // A cross-store failure must remain recoverable; never manufacture a competing END.
                            entry.active = false;
                            entry.quarantined = true;
                        }
                    } catch (Exception persistenceFailure) {
                        entry.lastFailure = persistenceFailure;
                        entry.active = false;
                        entry.quarantined = true;
                    }
                } else {
                    entry.active = false;
                }
            } finally {
                if (execution != null) tokens.remove(execution.id(), token);
                try {
                    if (session != null) session.close();
                    if (gate) entry.runtime.afterExecution();
                } catch (Exception releaseFailure) { entry.lastFailure = releaseFailure; entry.active = false; }
                finally {
                    if (gate) entry.runtime.mutationGate().unlock();
                    Thread.interrupted();
                    release();
                }
            }
        }
    }
    private static String stableMessage(Exception failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
    public synchronized boolean isQuarantined(WorkspaceKey key) {
        Entry entry = workspaces.get(key);
        return entry != null && (entry.quarantined || (closed && entry.slot.availablePermits() == 0));
    }
    public synchronized boolean safelyStopped() {
        return closed && workspaces.values().stream().allMatch(e -> e.slot.availablePermits() == 1);
    }
    public synchronized Optional<Exception> lastFailure(WorkspaceKey key) {
        Entry entry = workspaces.get(key);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.lastFailure);
    }
    @Override public void close() {
        ThreadPoolExecutor pool;
        synchronized (this) {
            closed = true; running = false;
            workspaces.values().forEach(e -> e.active = false);
            tokens.values().forEach(CancellationToken::cancel);
            workspaces.values().forEach(e -> { if (e.runtime != null) e.runtime.broker().close(); });
            pool = workers;
            if (dispatcher != null) dispatcher.interrupt();
        }
        signalWork();
        if (pool != null) {
            for (Runnable pending : pool.shutdownNow()) ((Reservation) pending).discard();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        synchronized (this) {
            // Host owns production runtime dependencies. The legacy adapter closes only safely drained ownership.
            if (legacyWorkspace != null) for (Entry entry : workspaces.values()) {
                if (entry.slot.availablePermits() == 1) try { entry.owner.close(); } catch (Exception e) { entry.lastFailure = e; }
            }
        }
    }
    private static final class Entry {
        final WorkspaceIdentity identity;
        final WorkspaceOwnerLease owner;
        final WorkspaceRuntime runtime;
        final TopLevelExecutionRunner runner;
        final BooleanSupplier gate;
        final Semaphore slot = new Semaphore(1);
        volatile boolean active = true;
        volatile boolean quarantined;
        volatile SharedExecutionResourceLeases.Lease retainedResources;
        volatile Exception lastFailure;
        Entry(WorkspaceIdentity identity, WorkspaceOwnerLease owner, WorkspaceRuntime runtime,
              TopLevelExecutionRunner runner, BooleanSupplier gate) {
            this.identity = identity; this.owner = owner; this.runtime = runtime; this.runner = runner; this.gate = gate;
        }
    }
}
