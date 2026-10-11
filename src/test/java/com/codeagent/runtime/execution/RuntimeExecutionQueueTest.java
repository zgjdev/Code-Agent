package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeExecutionQueueTest {

    @Test void cannotReadOrCancelAnUnregisteredWorkspace(@TempDir Path temporary) throws Exception {
        Path own = java.nio.file.Files.createDirectory(temporary.resolve("own"));
        Path other = java.nio.file.Files.createDirectory(temporary.resolve("other"));
        try (var store = new RuntimeExecutionStore(temporary.resolve("execution.db"));
             var scheduler = new WorkspaceAwareExecutionScheduler(store, own, (execution, token) -> TopLevelExecutionResult.succeeded("done"))) {
            RuntimeExecution foreign = store.enqueue(other, "foreign", "private task", null);
            scheduler.start();
            var queue = new RuntimeExecutionQueue(store, scheduler, own);
            assertTrue(queue.find(foreign.id()).isEmpty());
            assertEquals(CancelRequestResult.NOT_FOUND, queue.cancel(foreign.id()));
            assertEquals(CancelRequestResult.NOT_FOUND, scheduler.cancel(foreign.id()));
            assertEquals(ExecutionStatus.ENQUEUED, store.find(foreign.id()).orElseThrow().status());
            assertNull(store.find(foreign.id()).orElseThrow().cancelRequestedAt());
        }
    }

    @Test
    void ordinaryAndExplicitModesShareOneDurableSubmissionApi(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"));
             WorkspaceAwareExecutionScheduler scheduler = new WorkspaceAwareExecutionScheduler(
                     store, workspace, (execution, token) -> TopLevelExecutionResult.succeeded("done"))) {
            RuntimeExecutionQueue queue = new RuntimeExecutionQueue(store, scheduler, workspace);

            RuntimeExecution ordinary = queue.submit("session", "ordinary", null);
            RuntimeExecution react = queue.submit("session", "react", ExecutionMode.REACT);
            RuntimeExecution plan = queue.submit("session", "plan", ExecutionMode.PLAN);

            assertNull(ordinary.explicitMode());
            assertEquals(ExecutionMode.REACT, react.explicitMode());
            assertEquals(ExecutionMode.PLAN, plan.explicitMode());
            assertEquals(3, store.list(workspace, 10).size());
            assertTrue(queue.hasNonTerminal("session"));
        }
    }
}
