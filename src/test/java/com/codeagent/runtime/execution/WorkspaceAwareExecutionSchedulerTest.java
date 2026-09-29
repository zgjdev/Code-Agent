package com.codeagent.runtime.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceAwareExecutionSchedulerTest {

    @Test
    void bindsClaimsToOneWorkspaceAndSerializesDifferentSessions(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("current");
        Path otherWorkspace = tempDir.resolve("other");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"));
             WorkspaceAwareExecutionScheduler scheduler = new WorkspaceAwareExecutionScheduler(
                     store, workspace, (execution, token) -> {
                         int call = calls.incrementAndGet();
                         int nowActive = active.incrementAndGet();
                         maxActive.accumulateAndGet(nowActive, Math::max);
                         try {
                             if (call == 1) {
                                 firstStarted.countDown();
                                 assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                             } else {
                                 secondFinished.countDown();
                             }
                             return TopLevelExecutionResult.succeeded("result-" + call);
                         } finally {
                             active.decrementAndGet();
                         }
                     })) {
            RuntimeExecution first = store.enqueue(workspace, "session-a", "one", null);
            RuntimeExecution second = store.enqueue(workspace, "session-b", "two", null);
            RuntimeExecution other = store.enqueue(otherWorkspace, "session-c", "other", null);

            scheduler.start();
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertEquals(ExecutionStatus.RUNNING, store.find(first.id()).orElseThrow().status());
            assertEquals(ExecutionStatus.ENQUEUED, store.find(second.id()).orElseThrow().status());
            assertEquals(ExecutionStatus.ENQUEUED, store.find(other.id()).orElseThrow().status());

            releaseFirst.countDown();
            assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
            awaitStatus(store, second.id(), ExecutionStatus.COMPLETED);
            assertEquals(1, maxActive.get());
            assertEquals(ExecutionStatus.ENQUEUED, store.find(other.id()).orElseThrow().status());
        }
    }

    @Test
    void durableCancelIsVisibleToRunnerAndWinsCompletion(@TempDir Path tempDir) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"));
             WorkspaceAwareExecutionScheduler scheduler = new WorkspaceAwareExecutionScheduler(
                     store, tempDir, (execution, token) -> {
                         started.countDown();
                         while (!token.isCancelled()) {
                             Thread.onSpinWait();
                         }
                         return TopLevelExecutionResult.succeeded("too late");
                     })) {
            RuntimeExecution execution = store.enqueue(tempDir, "session", "prompt", null);
            scheduler.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));

            assertEquals(CancelRequestResult.REQUESTED, scheduler.cancel(execution.id()));
            RuntimeExecution terminal = awaitStatus(store, execution.id(), ExecutionStatus.CANCELED);
            assertEquals(ExecutionOutcome.CANCELED, terminal.outcome());
        }
    }

    @Test
    void runtimeShutdownRequeuesInsteadOfPretendingUserCancellation(@TempDir Path tempDir) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        RuntimeExecution execution;
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"))) {
            execution = store.enqueue(tempDir, "session", "prompt", null);
            WorkspaceAwareExecutionScheduler scheduler = new WorkspaceAwareExecutionScheduler(
                    store, tempDir, (ignored, token) -> {
                        started.countDown();
                        while (true) {
                            Thread.sleep(100);
                        }
                    });
            scheduler.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            scheduler.close();

            RuntimeExecution recovered = store.find(execution.id()).orElseThrow();
            assertEquals(ExecutionStatus.ENQUEUED, recovered.status());
            assertNull(recovered.outcome());
            assertEquals("runtime_shutdown", recovered.interruptionReason());
        }
    }

    @Test
    void claimGateCanBlockAndLaterReleaseWorkspace(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("workspace");
        java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean(false);
        CountDownLatch ran = new CountDownLatch(1);
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"));
             WorkspaceAwareExecutionScheduler scheduler = new WorkspaceAwareExecutionScheduler(
                     store, workspace, (execution, token) -> {
                         ran.countDown();
                         return TopLevelExecutionResult.succeeded("done");
                     }, allowed::get)) {
            store.enqueue(workspace, "session", "prompt", null);
            scheduler.start();
            scheduler.signalWork();
            assertFalse(ran.await(150, TimeUnit.MILLISECONDS));

            allowed.set(true);
            scheduler.signalWork();
            assertTrue(ran.await(2, TimeUnit.SECONDS));
        }
    }

    private static RuntimeExecution awaitStatus(
            RuntimeExecutionStore store, String id, ExecutionStatus expected) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        RuntimeExecution current;
        do {
            current = store.find(id).orElseThrow();
            if (current.status() == expected) {
                return current;
            }
            Thread.sleep(10);
        } while (Instant.now().isBefore(deadline));
        fail("Timed out waiting for " + expected + ", current=" + current.status());
        return current;
    }
}
