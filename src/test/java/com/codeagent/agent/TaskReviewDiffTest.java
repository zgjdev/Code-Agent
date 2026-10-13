package com.codeagent.agent;

import com.codeagent.plan.TaskResourceClaims;
import com.codeagent.snapshot.SideGitManager;
import com.codeagent.snapshot.SnapshotConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskReviewDiffTest {
    @TempDir Path temp;

    @Test
    void workspaceScopeUsesActualManagerExcludesAcrossSaveAndResume() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("secret.txt"), "excluded-secret\n");
        Files.writeString(project.resolve("source.txt"), "old\n");
        var config = new SnapshotConfig(true, temp.resolve("snapshots"), 50, List.of(".git", "secret.txt"));
        var side = new SideGitManager(project, config);
        var claims = new TaskResourceClaims(List.of(), List.of(), true);
        var tracker = TaskWorkspaceDiffTracker.start(project, claims, side);
        var transientTracker = TaskWorkspaceDiffTracker.start(project, claims, side, false);
        assertFalse(transientTracker.baseline().hashes().containsKey("secret.txt"));
        assertEquals("", transientTracker.baseline().snapshotCommitId());
        assertFalse(tracker.baseline().hashes().containsKey("secret.txt"));
        assertTrue(side.readTaskBaselineFile(tracker.baseline().snapshotCommitId(), "secret.txt", 1024).isEmpty());
        Files.writeString(project.resolve("secret.txt"), "new-secret\n");
        Files.writeString(project.resolve("source.txt"), "new\n");
        var restored = TaskWorkspaceDiffTracker.resume(project, claims, tracker.baseline(), new SideGitManager(project, config));
        assertTrue(restored.reviewDiff().complete());
        assertFalse(restored.reviewDiff().patch().contains("secret"));
        assertTrue(restored.reviewDiff().patch().contains("-old\n+new"));
    }

    @Test
    void missingSnapshotAndDisabledStorageDoNotFabricateOldText() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("a.txt"), "original\n");
        var config = new SnapshotConfig(true, temp.resolve("snapshots"), 50, List.of(".git"));
        var side = new SideGitManager(project, config);
        var claims = new TaskResourceClaims(List.of(), List.of("a.txt"), false);
        var baseline = TaskWorkspaceDiffTracker.start(project, claims, side).baseline();
        Files.delete(side.gitDir().resolve("refs/codeagent/task-baselines/" + baseline.snapshotCommitId()));
        Files.writeString(project.resolve("a.txt"), "after\n");
        assertFalse(TaskWorkspaceDiffTracker.resume(project, claims, baseline, side).reviewDiff().complete());
        var disabled = new SideGitManager(project, new SnapshotConfig(false, temp.resolve("disabled"), 50, List.of()));
        var tracker = TaskWorkspaceDiffTracker.start(project, claims, disabled);
        assertEquals("", tracker.baseline().snapshotCommitId());
        assertFalse(Files.exists(disabled.gitDir()));
        Files.writeString(project.resolve("a.txt"), "after-again\n");
        assertTrue(tracker.reviewDiff().complete(), "disabled durable storage still allows process-local review");
    }

    @Test
    void newlineOnlyChangeProducesActualPatch() throws Exception {
        Files.writeString(temp.resolve("a.txt"), "same");
        var tracker = TaskWorkspaceDiffTracker.start(temp,
                new TaskResourceClaims(List.of(), List.of("a.txt"), false));
        Files.writeString(temp.resolve("a.txt"), "same\n");
        var diff = tracker.reviewDiff();
        assertTrue(diff.complete());
        assertTrue(diff.patch().contains("-same"));
        assertTrue(diff.patch().contains("+same"));
        assertTrue(diff.patch().contains("No newline at end of file"));
    }

    @Test
    void persistsScopedOldTextWithoutMovingHeadAndRestoresFullPatch() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("owned.txt"), "old-value\n");
        Files.writeString(project.resolve("other.txt"), "private-other\n");
        SnapshotConfig config = new SnapshotConfig(true, temp.resolve("snapshots"), 50, List.of(".git"));
        SideGitManager side = new SideGitManager(project, config);
        String head = side.preTurnSnapshot("turn", "test").commitId();
        byte[] index = Files.readAllBytes(side.gitDir().resolve("index"));
        TaskResourceClaims claims = new TaskResourceClaims(List.of(), List.of("owned.txt"), false);
        TaskWorkspaceDiffTracker tracker = TaskWorkspaceDiffTracker.start(project, claims, side);
        String json = new ObjectMapper().writeValueAsString(tracker.baseline());
        assertFalse(json.contains("old-value"));
        assertFalse(json.contains("private-other"));
        assertFalse(tracker.baseline().snapshotCommitId().isBlank());
        assertEquals(head, side.listSnapshots(1).get(0).commitId());
        assertArrayEquals(index, Files.readAllBytes(side.gitDir().resolve("index")));
        assertTrue(side.readTaskBaselineFile(tracker.baseline().snapshotCommitId(), "other.txt", 1024).isEmpty());
        Files.writeString(project.resolve("owned.txt"), "new-value\n");
        Files.writeString(project.resolve("other.txt"), "unrelated-change\n");
        TaskWorkspaceDiffTracker restored = TaskWorkspaceDiffTracker.resume(project, claims,
                new ObjectMapper().readValue(json, TaskWorkspaceDiffTracker.Baseline.class),
                new SideGitManager(project, config));
        TaskReviewDiff diff = restored.reviewDiff();
        assertTrue(diff.complete(), diff.issues().toString());
        assertTrue(diff.patch().contains("-old-value"), diff.patch());
        assertTrue(diff.patch().contains("+new-value"), diff.patch());
        assertFalse(diff.patch().contains("other.txt"));
        assertEquals(1, restored.diff().additions());
        assertEquals(1, restored.diff().deletions());
        Files.writeString(project.resolve("owned.txt"), "third-value\n");
        assertTrue(restored.reviewDiff().patch().contains("-old-value"));
    }

    @Test
    void addedDeletedAndModifiedFilesAppearInPatch() throws Exception {
        Files.writeString(temp.resolve("removed.txt"), "gone\n");
        TaskWorkspaceDiffTracker tracker = TaskWorkspaceDiffTracker.start(temp,
                new TaskResourceClaims(List.of(), List.of("./"), false));
        Files.delete(temp.resolve("removed.txt"));
        Files.writeString(temp.resolve("added.txt"), "created\n");
        TaskReviewDiff diff = tracker.reviewDiff();
        assertTrue(diff.complete());
        assertTrue(diff.patch().contains("/dev/null"));
        assertTrue(diff.patch().contains("-gone"));
        assertTrue(diff.patch().contains("+created"));
    }

    @Test
    void legacyMissingOldTextBinaryAndOversizedPatchAreIncomplete() throws Exception {
        Path source = temp.resolve("a.txt");
        Files.writeString(source, "before\n");
        TaskResourceClaims claims = new TaskResourceClaims(List.of(), List.of("a.txt"), false);
        TaskWorkspaceDiffTracker tracker = TaskWorkspaceDiffTracker.start(temp, claims);
        TaskWorkspaceDiffTracker legacy = TaskWorkspaceDiffTracker.resume(temp, claims, tracker.baseline());
        Files.writeString(source, "after\n");
        assertFalse(legacy.reviewDiff().complete());
        Files.write(source, new byte[]{0, 1});
        assertFalse(tracker.reviewDiff().complete());
        Files.writeString(source, "changed-line\n".repeat(10000));
        TaskReviewDiff bounded = tracker.reviewDiff();
        assertFalse(bounded.complete());
        assertTrue(bounded.patch().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 48 * 1024);
    }

    @Test
    void restoredBaselineCannotExpandTaskScope() throws Exception {
        Files.writeString(temp.resolve("outside.txt"), "before");
        TaskWorkspaceDiffTracker tracker = TaskWorkspaceDiffTracker.start(temp,
                new TaskResourceClaims(List.of(), List.of("outside.txt"), false));
        assertThrows(java.io.IOException.class, () -> TaskWorkspaceDiffTracker.resume(temp,
                new TaskResourceClaims(List.of(), List.of("owned.txt"), false), tracker.baseline()));
    }
}
