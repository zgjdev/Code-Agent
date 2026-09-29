package com.codeagent.runtime.execution;

import com.codeagent.runtime.CancellationContext;
import com.codeagent.runtime.CancellationToken;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class WorkspaceAwareExecutionScheduler implements AutoCloseable {
    private final RuntimeExecutionStore store;
    private final Path workspace;
    private final TopLevelExecutionRunner runner;
    private final BooleanSupplier claimAllowed;
    private final Object wakeup = new Object();
    private final ConcurrentHashMap<String, CancellationToken> runningTokens = new ConcurrentHashMap<>();
    private final AtomicReference<String> runningExecutionId = new AtomicReference<>();
    private volatile boolean running;
    private ExecutorService worker;

    public WorkspaceAwareExecutionScheduler(
            RuntimeExecutionStore store, Path workspace, TopLevelExecutionRunner runner) {
        this(store, workspace, runner, () -> true);
    }

    public WorkspaceAwareExecutionScheduler(
            RuntimeExecutionStore store,
            Path workspace,
            TopLevelExecutionRunner runner,
            BooleanSupplier claimAllowed) {
        this.store = store;
        this.workspace = workspace.toAbsolutePath().normalize();
        this.runner = runner;
        this.claimAllowed = claimAllowed;
    }

    public synchronized void start() throws SQLException {
        if (running) {
            return;
        }
        store.recoverStaleRunning(workspace);
        running = true;
        worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "codeagent-execution-worker");
            thread.setDaemon(true);
            return thread;
        });
        worker.submit(this::workerLoop);
    }

    public void signalWork() {
        synchronized (wakeup) {
            wakeup.notifyAll();
        }
    }

    public CancelRequestResult cancel(String executionId) throws SQLException {
        CancelRequestResult result = store.requestCancel(executionId);
        if (result == CancelRequestResult.REQUESTED || result == CancelRequestResult.ALREADY_REQUESTED) {
            CancellationToken token = runningTokens.get(executionId);
            if (token != null) {
                token.cancel();
            }
        }
        signalWork();
        return result;
    }

    private void workerLoop() {
        while (running) {
            try {
                if (!claimAllowed.getAsBoolean()) {
                    waitForWork();
                    continue;
                }
                Optional<RuntimeExecution> claimed = store.claimNext(workspace);
                if (claimed.isEmpty()) {
                    waitForWork();
                    continue;
                }
                runOne(claimed.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                if (!running) {
                    return;
                }
                waitAfterFailure();
            }
        }
    }

    private void runOne(RuntimeExecution execution) throws SQLException {
        String id = execution.id();
        CancellationToken token = CancellationContext.startRun();
        runningExecutionId.set(id);
        runningTokens.put(id, token);
        try {
            TopLevelExecutionResult result = runner.run(execution, token);
            if (!running) {
                store.interruptForRecovery(id, "runtime_shutdown");
                return;
            }
            store.complete(id, result.outcome(), result.result(), result.error());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            store.interruptForRecovery(id, running ? "worker_interrupted" : "runtime_shutdown");
        } catch (Exception failure) {
            if (running) {
                store.complete(id, ExecutionOutcome.FAILED, null, stableMessage(failure));
            } else {
                store.interruptForRecovery(id, "runtime_shutdown");
            }
        } finally {
            runningTokens.remove(id);
            runningExecutionId.compareAndSet(id, null);
            CancellationContext.clear(token);
            Thread.interrupted();
        }
    }

    private void waitForWork() throws InterruptedException {
        synchronized (wakeup) {
            wakeup.wait(100L);
        }
    }

    private void waitAfterFailure() {
        try {
            synchronized (wakeup) {
                wakeup.wait(100L);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String stableMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    @Override
    public synchronized void close() {
        running = false;
        signalWork();
        if (worker == null) {
            return;
        }
        worker.shutdownNow();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        String id = runningExecutionId.get();
        if (id != null) {
            try {
                RuntimeExecution current = store.find(id).orElse(null);
                if (current != null && current.status() == ExecutionStatus.RUNNING) {
                    store.interruptForRecovery(id, "runtime_shutdown");
                }
            } catch (SQLException ignored) {
                // Startup recovery will handle any RUNNING row left behind.
            }
        }
        worker = null;
    }
}
