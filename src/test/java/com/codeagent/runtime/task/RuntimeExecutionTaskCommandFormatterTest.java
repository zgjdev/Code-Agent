package com.codeagent.runtime.task;

import com.codeagent.runtime.execution.RuntimeExecutionStore;
import com.codeagent.runtime.execution.RuntimeExecutionQueue;
import com.codeagent.runtime.execution.TopLevelExecutionResult;
import com.codeagent.runtime.execution.WorkspaceAwareExecutionScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeExecutionTaskCommandFormatterTest {

    @Test
    void addListLogAndCancelUseRuntimeExecutionStore(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("workspace");
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"));
             WorkspaceAwareExecutionScheduler scheduler = new WorkspaceAwareExecutionScheduler(
                     store, workspace, (execution, token) -> TopLevelExecutionResult.succeeded("done"))) {
            RuntimeExecutionQueue queue = new RuntimeExecutionQueue(store, scheduler, workspace);
            String added = TaskCommandFormatter.handle(
                    queue, "session-1", "add inspect project");
            String id = store.list(workspace, 1).get(0).id();

            assertTrue(added.contains(id));
            assertTrue(TaskCommandFormatter.handle(
                    queue, "session-1", "list").contains("inspect project"));
            assertTrue(TaskCommandFormatter.handle(
                    queue, "session-1", "log " + id).contains("session-1"));
            assertTrue(TaskCommandFormatter.handle(
                    queue, "session-1", "cancel " + id).contains("已请求取消"));
        }
    }
}
