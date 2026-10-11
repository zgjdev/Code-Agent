package com.codeagent.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Cancellation of a Future is not proof that the task has physically stopped. */
public final class ExecutorDrain {
    private ExecutorDrain() { }
    public static void stopAndAwait(ExecutorService executor) {
        executor.shutdownNow();
        boolean interrupted = Thread.interrupted();
        try {
            while (!executor.isTerminated()) {
                try { executor.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
