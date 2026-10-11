package com.codeagent.runtime.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionShutdownQuarantineTest {
    @Test void retainsOwnershipWhenRunnerIgnoresInterrupts(@TempDir Path root) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var stopped = new CountDownLatch(1);
        try (var store = new RuntimeExecutionStore(root.resolve("executions.db"))) {
            RuntimeExecution first = store.enqueue(root, "first", "one", null);
            RuntimeExecution next = store.enqueue(root, "second", "two", null);
            var scheduler = new WorkspaceAwareExecutionScheduler(store, root, (execution, token) -> {
                entered.countDown();
                try {
                    while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { }
                    return TopLevelExecutionResult.succeeded("stopped");
                } finally { stopped.countDown(); }
            });
            try {
                scheduler.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
                scheduler.close();
                assertTrue(scheduler.isQuarantined(new WorkspaceIdentityResolver().resolve(root).key()));
                assertEquals(ExecutionStatus.RUNNING, store.find(first.id()).orElseThrow().status());
                assertEquals(ExecutionStatus.ENQUEUED, store.find(next.id()).orElseThrow().status());
                assertThrows(IllegalStateException.class, scheduler::start);
            } finally {
                release.countDown(); assertTrue(stopped.await(5, TimeUnit.SECONDS)); scheduler.close();
            }
            assertTrue(scheduler.safelyStopped());
            assertEquals(ExecutionStatus.ENQUEUED, store.find(first.id()).orElseThrow().status());
        }
    }
}
