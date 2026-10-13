package com.codeagent.runtime.task;

import com.codeagent.runtime.execution.RuntimeExecutionStore;
import com.codeagent.runtime.execution.RuntimeExecutionQueue;
import com.codeagent.runtime.execution.TopLevelExecutionResult;
import com.codeagent.runtime.execution.WorkspaceAwareExecutionScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuntimeExecutionTaskCommandFormatterTest {

    @Test
    void rejectsAddButListLogAndCancelUseRuntimeExecutionStore(@TempDir Path tempDir) throws Exception {
        Path workspace = java.nio.file.Files.createDirectory(tempDir.resolve("workspace"));
        java.util.concurrent.locks.ReentrantLock gate = new java.util.concurrent.locks.ReentrantLock();
        gate.lock();
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"));
             WorkspaceAwareExecutionScheduler scheduler = new WorkspaceAwareExecutionScheduler(
                     store, workspace, (execution, token) -> TopLevelExecutionResult.succeeded("done"), () -> !gate.isLocked())) {
            scheduler.start();
            RuntimeExecutionQueue queue = new RuntimeExecutionQueue(store, scheduler, workspace);
            String rejected = TaskCommandFormatter.handle(
                    queue, "session-1", "add inspect project");
            assertTrue(rejected.contains("未知 /task 子命令"));
            assertFalse(rejected.contains("/task add"));
            assertTrue(store.list(workspace, 20).isEmpty());
            String id = queue.submit("session-1", "inspect project", null).id();

            assertTrue(TaskCommandFormatter.handle(
                    queue, "session-1", "list").contains("inspect project"));
            assertTrue(TaskCommandFormatter.handle(
                    queue, "session-1", "log " + id).contains("session-1"));
            assertTrue(TaskCommandFormatter.handle(
                    queue, "session-1", "cancel " + id).contains("已请求取消"));
        } finally { gate.unlock(); }
    }

    @Test
    void legacyFormatterCannotEnqueueAdd(@TempDir Path tempDir) throws Exception {
        try (var manager = new DurableTaskManager(tempDir.resolve("tasks.db"), task -> "done", 1)) {
            String rejected = TaskCommandFormatter.handle(manager, "add inspect project");
            assertTrue(rejected.contains("未知 /task 子命令"));
            assertFalse(rejected.contains("/task add"));
            assertTrue(manager.list(20).isEmpty());
        }
    }
}
