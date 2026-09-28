package com.codeagent.agent;

import com.codeagent.plan.TaskResourceClaims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        Path nestedTarget = tempDir.resolve("module-a/target/generated.txt");
        Path nestedNodeModules = tempDir.resolve("web/node_modules/package/index.js");
        Files.createDirectories(source.getParent());
        Files.createDirectories(target.getParent());
        Files.createDirectories(nestedTarget.getParent());
        Files.createDirectories(nestedNodeModules.getParent());
        Files.writeString(source, "before\n");
        Files.writeString(target, "before\n");
        Files.writeString(nestedTarget, "before\n");
        Files.writeString(nestedNodeModules, "before\n");
        TaskWorkspaceDiffTracker tracker = TaskWorkspaceDiffTracker.start(
                tempDir,
                new TaskResourceClaims(List.of(), List.of(), true));

        Files.writeString(source, "after\n");
        Files.writeString(target, "ignored\n");
        Files.writeString(nestedTarget, "ignored\n");
        Files.writeString(nestedNodeModules, "ignored\n");

        TaskWorkspaceDiffTracker.DiffSummary summary = tracker.diff();
        assertEquals(List.of("src/App.java"), summary.changedPaths());
    }

    @Test
    void workspaceWriteAppliesPathGlobToFullRelativePath() throws Exception {
        String previous = System.getProperty("codeagent.snapshot.excludes");
        System.setProperty("codeagent.snapshot.excludes", "generated/**");
        try {
            Path ignored = tempDir.resolve("generated/client/output.txt");
            Path tracked = tempDir.resolve("src/output.txt");
            Files.createDirectories(ignored.getParent());
            Files.createDirectories(tracked.getParent());
            Files.writeString(ignored, "before\n");
            Files.writeString(tracked, "before\n");
            TaskWorkspaceDiffTracker tracker = TaskWorkspaceDiffTracker.start(
                    tempDir,
                    new TaskResourceClaims(List.of(), List.of(), true));

            Files.writeString(ignored, "ignored\n");
            Files.writeString(tracked, "after\n");

            assertEquals(List.of("src/output.txt"), tracker.diff().changedPaths());
        } finally {
            if (previous == null) {
                System.clearProperty("codeagent.snapshot.excludes");
            } else {
                System.setProperty("codeagent.snapshot.excludes", previous);
            }
        }
    }

    @Test
    void resumedTrackerUsesPersistedHashBaselineInsteadOfCurrentWorkspace() throws Exception {
        Path source = tempDir.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "before\n");
        TaskResourceClaims claims = new TaskResourceClaims(
                List.of(), List.of("src/App.java"), false);
        TaskWorkspaceDiffTracker initial = TaskWorkspaceDiffTracker.start(tempDir, claims);
        TaskWorkspaceDiffTracker.Baseline persisted = initial.baseline();

        Files.writeString(source, "after crash\n");
        TaskWorkspaceDiffTracker resumed = TaskWorkspaceDiffTracker.resume(
                tempDir, claims, persisted);

        TaskWorkspaceDiffTracker.DiffSummary summary = resumed.diff();
        assertTrue(summary.changed());
        assertEquals(List.of("src/App.java"), summary.changedPaths());
        assertEquals(1, summary.lineStatsUnavailableFiles(),
                "持久化 baseline 不保存源码正文，恢复后仍应通过 hash 证明变化");
    }

    @Test
    void persistedBaselineContainsOnlyPathsAndHashes() throws Exception {
        Path source = tempDir.resolve("src/Secret.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "sensitive-source-body\n");

        TaskWorkspaceDiffTracker tracker = TaskWorkspaceDiffTracker.start(
                tempDir,
                new TaskResourceClaims(List.of(), List.of("src/Secret.java"), false));

        TaskWorkspaceDiffTracker.Baseline baseline = tracker.baseline();
        assertEquals(List.of("src/Secret.java"), baseline.hashes().keySet().stream().toList());
        assertEquals(64, baseline.hashes().get("src/Secret.java").length());
        assertFalse(baseline.toString().contains("sensitive-source-body"));
    }

    @Test
    void baselineDefensivelyCopiesPersistedHashes() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("src/App.java", "a".repeat(64));

        TaskWorkspaceDiffTracker.Baseline baseline =
                new TaskWorkspaceDiffTracker.Baseline(mutable);
        mutable.clear();

        assertEquals(1, baseline.hashes().size());
    }

    @Test
    void baselineTextCacheIsBoundedAcrossWorkspace() throws Exception {
        Path sources = tempDir.resolve("src");
        Files.createDirectories(sources);
        String oneMiB = "x".repeat(1024 * 1024);
        for (int i = 0; i < 17; i++) {
            Files.writeString(sources.resolve("File%02d.txt".formatted(i)), oneMiB);
        }
        TaskWorkspaceDiffTracker tracker = TaskWorkspaceDiffTracker.start(
                tempDir,
                new TaskResourceClaims(List.of(), List.of("src/"), false));

        for (int i = 0; i < 17; i++) {
            Files.writeString(sources.resolve("File%02d.txt".formatted(i)), oneMiB + "changed\n");
        }

        TaskWorkspaceDiffTracker.DiffSummary summary = tracker.diff();
        assertEquals(17, summary.changedFiles());
        assertEquals(1, summary.lineStatsUnavailableFiles(),
                "全局 16 MiB 上限后，后续文本仍比较 hash，但不缓存 baseline 正文");
    }

    @Test
    void invalidExcludePatternBecomesCheckedDiffFailure() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/App.java"), "content\n");
        String previous = System.getProperty("codeagent.snapshot.excludes");
        System.setProperty("codeagent.snapshot.excludes", "*[");
        try {
            assertThrows(IOException.class, () -> TaskWorkspaceDiffTracker.start(
                    tempDir,
                    new TaskResourceClaims(List.of(), List.of(), true)));
        } finally {
            if (previous == null) {
                System.clearProperty("codeagent.snapshot.excludes");
            } else {
                System.setProperty("codeagent.snapshot.excludes", previous);
            }
        }
    }
}
