package com.codeagent.tool;

import com.codeagent.policy.PathGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolConflictAwareBatchSelectorTest {

    @TempDir
    Path projectRoot;

    @Test
    void preservesConflictOrderInsteadOfLettingLaterCallOvertake() {
        ToolConflictAwareBatchSelector selector = selector();

        List<List<ToolRegistry.ToolInvocation>> batches = selector.partition(List.of(
                call("write-a", "write_file", "{\"path\":\"src/A.java\",\"content\":\"a\"}"),
                call("read-a", "read_file", "{\"path\":\"src/A.java\"}"),
                call("write-b", "write_file", "{\"path\":\"src/B.java\",\"content\":\"b\"}")
        ), 4);

        assertEquals(List.of(
                        List.of("write-a"),
                        List.of("read-a", "write-b")),
                batches.stream()
                        .map(batch -> batch.stream().map(ToolRegistry.ToolInvocation::id).toList())
                        .toList());
    }

    @Test
    void groupsDisjointWritesIntoSameBatch() {
        List<List<ToolRegistry.ToolInvocation>> batches = selector().partition(List.of(
                call("a", "write_file", "{\"path\":\"src/A.java\",\"content\":\"a\"}"),
                call("b", "write_file", "{\"path\":\"src/B.java\",\"content\":\"b\"}")
        ), 4);

        assertEquals(1, batches.size());
        assertEquals(List.of("a", "b"), batches.get(0).stream()
                .map(ToolRegistry.ToolInvocation::id)
                .toList());
    }

    @Test
    void workspaceWriteStartsNewBatchBeforeWorkspaceRead() {
        List<List<ToolRegistry.ToolInvocation>> batches = selector().partition(List.of(
                call("command", "execute_command", "{\"command\":\"mvn test\"}"),
                call("read", "read_file", "{\"path\":\"README.md\"}")
        ), 4);

        assertEquals(List.of(
                        List.of("command"),
                        List.of("read")),
                batches.stream()
                        .map(batch -> batch.stream().map(ToolRegistry.ToolInvocation::id).toList())
                        .toList());
    }

    private ToolConflictAwareBatchSelector selector() {
        return new ToolConflictAwareBatchSelector(
                new ToolResourceClaimResolver(new PathGuard(projectRoot.toString())));
    }

    private ToolRegistry.ToolInvocation call(String id, String name, String arguments) {
        return new ToolRegistry.ToolInvocation(id, name, arguments);
    }
}
