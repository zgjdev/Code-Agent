package com.codeagent.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictAwareBatchSelectorTest {
    private final ConflictAwareBatchSelector selector = new ConflictAwareBatchSelector();

    @Test
    void readReadIsCompatible() {
        assertFalse(selector.conflicts(claims(List.of("src/"), List.of(), false),
                claims(List.of("src/main/App.java"), List.of(), false)));
    }

    @Test
    void writeWriteAndWriteReadOverlapConflict() {
        TaskResourceClaims writer = claims(List.of(), List.of("src/main/App.java"), false);

        assertTrue(selector.conflicts(writer,
                claims(List.of(), List.of("src/main/App.java"), false)));
        assertTrue(selector.conflicts(writer,
                claims(List.of("src/main/App.java"), List.of(), false)));
    }

    @Test
    void ancestorDirectoryOverlapsDescendantFile() {
        assertTrue(selector.conflicts(
                claims(List.of(), List.of("src/main/"), false),
                claims(List.of("src/main/com/acme/App.java"), List.of(), false)));
    }

    @Test
    void disjointWritesAreCompatible() {
        assertFalse(selector.conflicts(
                claims(List.of(), List.of("src/main/App.java"), false),
                claims(List.of(), List.of("README.md"), false)));
    }

    @Test
    void workspaceWriteConflictsWithEveryOtherTask() {
        assertTrue(selector.conflicts(
                claims(List.of(), List.of(), true),
                claims(List.of("README.md"), List.of(), false)));
    }

    @Test
    void selectsAtMostFourTasksInInputOrder() {
        List<Task> ready = List.of(
                task("a", "a.txt"), task("b", "b.txt"), task("c", "c.txt"),
                task("d", "d.txt"), task("e", "e.txt"));

        assertEquals(List.of("a", "b", "c", "d"), selector.select(ready, 4).stream()
                .map(Task::getId).toList());
    }

    @Test
    void skipsConflictingMiddleTaskAndSelectsCompatibleLaterTask() {
        Task first = task("first", "src/App.java");
        Task conflicting = new Task("conflict", "read app", Task.TaskType.FILE_READ, List.of(),
                claims(List.of("src/"), List.of(), false), List.of(), Set.of());
        Task later = task("later", "README.md");

        assertEquals(List.of("first", "later"), selector.select(
                        List.of(first, conflicting, later), 4).stream()
                .map(Task::getId).toList());
    }

    @Test
    void fallsBackToFirstTaskWhenConcurrencyIsNonPositive() {
        Task first = task("first", "a.txt");
        Task second = task("second", "b.txt");

        assertEquals(List.of(first), selector.select(List.of(first, second), 0));
    }

    private Task task(String id, String writePath) {
        return new Task(id, id, Task.TaskType.FILE_WRITE, List.of(),
                claims(List.of(), List.of(writePath), false), List.of(), Set.of());
    }

    private TaskResourceClaims claims(List<String> reads, List<String> writes, boolean workspaceWrite) {
        return new TaskResourceClaims(reads, writes, workspaceWrite);
    }
}
