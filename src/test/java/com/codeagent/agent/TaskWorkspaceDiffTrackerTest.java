package com.codeagent.agent;

import com.codeagent.plan.TaskResourceClaims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskWorkspaceDiffTrackerTest {
    @TempDir
    Path tempDir;

    @Test
    void computesHistogramLineStatsForDeclaredWriteFile() throws Exception {
        Path source = tempDir.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "a\nb\nc\n");
        TaskWorkspaceDiffTracker tracker = TaskWorkspaceDiffTracker.start(
                tempDir,
                new TaskResourceClaims(List.of(), List.of("src/App.java"), false));

        Files.writeString(source, "a\nx\nb\nc\n");

        TaskWorkspaceDiffTracker.DiffSummary summary = tracker.diff();
        assertTrue(summary.changed());
        assertEquals(1, summary.changedFiles());
        assertEquals(1, summary.additions());
        assertEquals(0, summary.deletions());
        assertEquals(List.of("src/App.java"), summary.changedPaths());
        assertFalse(summary.digest().isBlank());
    }

    @Test
    void tracksAddedAndDeletedFilesInsideDeclaredDirectory() throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        Path removed = sourceDir.resolve("Old.java");
        Files.writeString(removed, "old\n");
        TaskWorkspaceDiffTracker tracker = TaskWorkspaceDiffTracker.start(
                tempDir,
                new TaskResourceClaims(List.of(), List.of("src/"), false));

        Files.delete(removed);
        Files.writeString(sourceDir.resolve("New.java"), "new\n");

        TaskWorkspaceDiffTracker.DiffSummary summary = tracker.diff();
        assertTrue(summary.changed());
        assertEquals(2, summary.changedFiles());
        assertEquals(1, summary.additions());
        assertEquals(1, summary.deletions());
        assertEquals(List.of("src/New.java", "src/Old.java"), summary.changedPaths());
    }

    @Test
    void ignoresChangesOutsideDeclaredWritePaths() throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        Path owned = sourceDir.resolve("Owned.java");
        Path unrelated = sourceDir.resolve("Other.java");
        Files.writeString(owned, "before\n");
        Files.writeString(unrelated, "before\n");
        TaskWorkspaceDiffTracker tracker = TaskWorkspaceDiffTracker.start(
                tempDir,
                new TaskResourceClaims(List.of(), List.of("src/Owned.java"), false));

        Files.writeString(owned, "after\n");
        Files.writeString(unrelated, "also changed\n");

        TaskWorkspaceDiffTracker.DiffSummary summary = tracker.diff();
        assertEquals(List.of("src/Owned.java"), summary.changedPaths());
    }

    @Test
    void workspaceWriteUsesSideGitExcludes() throws Exception {
        Path source = tempDir.resolve("src/App.java");
        Path target = tempDir.resolve("target/generated.txt");
        Files.createDirectories(source.getParent());
        Files.createDirectories(target.getParent());
        Files.writeString(source, "before\n");
        Files.writeString(target, "before\n");
        TaskWorkspaceDiffTracker tracker = TaskWorkspaceDiffTracker.start(
                tempDir,
                new TaskResourceClaims(List.of(), List.of(), true));

        Files.writeString(source, "after\n");
        Files.writeString(target, "ignored\n");

        TaskWorkspaceDiffTracker.DiffSummary summary = tracker.diff();
        assertEquals(List.of("src/App.java"), summary.changedPaths());
    }
}
