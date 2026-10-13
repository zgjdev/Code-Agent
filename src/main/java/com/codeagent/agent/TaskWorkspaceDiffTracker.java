package com.codeagent.agent;

import com.codeagent.plan.TaskResourceClaims;
import com.codeagent.snapshot.SnapshotConfig;
import com.codeagent.snapshot.SideGitManager;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Captures a task-scoped workspace baseline and computes a real before/after diff.
 *
 * <p>Parallel tasks only inspect their declared write paths. A workspaceWrite task
 * is serialized by conflict-aware scheduling, so it may inspect the whole workspace
 * while honoring the same exclude list used by Side-Git snapshots.</p>
 */
public final class TaskWorkspaceDiffTracker {
    private static final int MAX_TEXT_DIFF_BYTES = 2 * 1024 * 1024;
    private static final long MAX_BASELINE_TEXT_CACHE_BYTES = 16L * 1024 * 1024;
    private static final int MAX_REVIEW_PATCH_BYTES = 48 * 1024;

    private final Path projectRoot;
    private final TaskResourceClaims claims;
    private final List<ExcludeRule> excludeRules;
    private final Map<String, FileSnapshot> baseline;
    private String snapshotCommitId = "";

    private TaskWorkspaceDiffTracker(Path projectRoot, TaskResourceClaims claims) throws IOException {
        this(projectRoot, claims, SnapshotConfig.fromEnvironment().excludes());
    }

    private TaskWorkspaceDiffTracker(Path projectRoot, TaskResourceClaims claims, List<String> excludes) throws IOException {
        Path root = projectRoot == null ? Path.of(".") : projectRoot;
        this.projectRoot = root.toAbsolutePath().normalize();
        this.claims = claims == null
                ? new TaskResourceClaims(List.of(), List.of(), false)
                : claims;
        this.excludeRules = compileExcludeRules(excludes);
        this.baseline = capture(true);
    }

    private TaskWorkspaceDiffTracker(Path projectRoot,
                                     TaskResourceClaims claims,
                                     Baseline persistedBaseline) throws IOException {
        this(projectRoot, claims, persistedBaseline, SnapshotConfig.fromEnvironment().excludes());
    }

    private TaskWorkspaceDiffTracker(Path projectRoot, TaskResourceClaims claims,
                                     Baseline persistedBaseline, List<String> excludes) throws IOException {
        Path root = projectRoot == null ? Path.of(".") : projectRoot;
        this.projectRoot = root.toAbsolutePath().normalize();
        this.claims = claims == null
                ? new TaskResourceClaims(List.of(), List.of(), false)
                : claims;
        this.excludeRules = compileExcludeRules(excludes);
        this.baseline = restoreBaseline(persistedBaseline);
        this.snapshotCommitId = persistedBaseline.snapshotCommitId();
    }

    public static TaskWorkspaceDiffTracker start(Path projectRoot, TaskResourceClaims claims) throws IOException {
        return new TaskWorkspaceDiffTracker(projectRoot, claims);
    }

    public static TaskWorkspaceDiffTracker start(Path projectRoot, TaskResourceClaims claims,
                                                  SideGitManager sideGit) throws IOException {
        return start(projectRoot, claims, sideGit, true);
    }

    public static TaskWorkspaceDiffTracker start(Path projectRoot, TaskResourceClaims claims,
                                                  SideGitManager sideGit, boolean persistText) throws IOException {
        TaskWorkspaceDiffTracker tracker = new TaskWorkspaceDiffTracker(projectRoot, claims,
                sideGit == null ? SnapshotConfig.fromEnvironment().excludes() : sideGit.config().excludes());
        if (sideGit != null && !sideGit.projectRoot().equals(tracker.projectRoot)) {
            throw new IOException("snapshot workspace mismatch");
        }
        if (sideGit != null && persistText) {
            Map<String, byte[]> text = new TreeMap<>();
            tracker.baseline.forEach((path, snapshot) -> {
                byte[] bytes = snapshot.textBytes();
                if (bytes != null) text.put(path, bytes);
            });
            tracker.snapshotCommitId = sideGit.saveTaskBaseline(text);
        }
        return tracker;
    }

    public static TaskWorkspaceDiffTracker resume(Path projectRoot,
                                                  TaskResourceClaims claims,
                                                  Baseline persistedBaseline) throws IOException {
        return new TaskWorkspaceDiffTracker(projectRoot, claims, persistedBaseline);
    }

    public static TaskWorkspaceDiffTracker resume(Path projectRoot, TaskResourceClaims claims,
                                                   Baseline persistedBaseline, SideGitManager sideGit) throws IOException {
        TaskWorkspaceDiffTracker tracker = new TaskWorkspaceDiffTracker(projectRoot, claims, persistedBaseline,
                sideGit == null ? SnapshotConfig.fromEnvironment().excludes() : sideGit.config().excludes());
        if (sideGit == null || tracker.snapshotCommitId.isBlank()) return tracker;
        if (!sideGit.projectRoot().equals(tracker.projectRoot)) throw new IOException("snapshot workspace mismatch");
        long remaining = MAX_BASELINE_TEXT_CACHE_BYTES;
        for (var entry : tracker.baseline.entrySet()) {
            // Unavailable snapshots retain the hash baseline; reviewDiff fails closed for changed files.
            byte[] bytes;
            try {
                bytes = sideGit.readTaskBaselineFile(tracker.snapshotCommitId, entry.getKey(),
                        (int) Math.min(MAX_TEXT_DIFF_BYTES, remaining)).orElse(null);
            } catch (IOException e) {
                bytes = null;
            }
            if (bytes != null && sha256(bytes).equals(entry.getValue().sha256()) && !looksBinary(bytes)) {
                entry.setValue(new FileSnapshot(entry.getValue().sha256(), bytes.length, bytes, true));
                remaining -= bytes.length;
            }
        }
        return tracker;
    }

    public Baseline baseline() {
        Map<String, String> hashes = new TreeMap<>();
        baseline.forEach((path, snapshot) -> hashes.put(path, snapshot.sha256()));
        return new Baseline(hashes, snapshotCommitId);
    }

    public TaskReviewDiff reviewDiff() throws IOException {
        Map<String, FileSnapshot> current = capture(false);
        TreeSet<String> paths = new TreeSet<>(baseline.keySet());
        paths.addAll(current.keySet());
        BoundedPatch output = new BoundedPatch();
        List<String> issues = new ArrayList<>();
        try (DiffFormatter formatter = new DiffFormatter(output)) {
            formatter.setContext(3);
            for (String path : paths) {
                FileSnapshot before = baseline.get(path);
                FileSnapshot after = current.get(path);
                if (sameContent(before, after)) continue;
                byte[] oldBytes = before == null ? new byte[0] : before.textBytes();
                byte[] newBytes = readCurrentText(path, after);
                if (oldBytes == null || newBytes == null || !validUtf8(oldBytes) || !validUtf8(newBytes)) {
                    if (issues.size() < 50) issues.add("diff text unavailable: " + path);
                    continue;
                }
                // Quoted path headers prevent newlines or control characters from forging patch structure.
                String oldPath = before == null ? "/dev/null" : quotePath("a/" + path);
                String newPath = after == null ? "/dev/null" : quotePath("b/" + path);
                output.write(("diff --git " + quotePath("a/" + path) + " " + quotePath("b/" + path)
                        + "\n--- " + oldPath + "\n+++ " + newPath + "\n").getBytes(StandardCharsets.UTF_8));
                RawText oldText = new RawText(oldBytes);
                RawText newText = new RawText(newBytes);
                EditList edits = new HistogramDiff().diff(RawTextComparator.DEFAULT, oldText, newText);
                if (edits.isEmpty() && oldText.size() > 0 && newText.size() > 0
                        && oldText.isMissingNewlineAtEnd() != newText.isMissingNewlineAtEnd()) {
                    edits.add(new Edit(oldText.size() - 1, oldText.size(), newText.size() - 1, newText.size()));
                }
                formatter.format(edits, oldText, newText);
            }
            formatter.flush();
        } catch (PatchLimitException e) {
            issues.add("diff truncated at " + MAX_REVIEW_PATCH_BYTES + " bytes");
        }
        return new TaskReviewDiff(output.text(), issues.isEmpty(), issues);
    }

    private static String quotePath(String path) {
        return org.eclipse.jgit.util.QuotedString.GIT_PATH.quote(path);
    }

    private static boolean validUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    private static final class PatchLimitException extends IOException { }

    private static final class BoundedPatch extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public void write(int value) throws IOException {
            if (bytes.size() >= MAX_REVIEW_PATCH_BYTES) throw new PatchLimitException();
            bytes.write(value);
        }
        @Override public void write(byte[] source, int offset, int length) throws IOException {
            int allowed = Math.min(length, MAX_REVIEW_PATCH_BYTES - bytes.size());
            bytes.write(source, offset, allowed);
            if (allowed < length) throw new PatchLimitException();
        }
        private String text() {
            byte[] raw = bytes.toByteArray();
            int length = raw.length;
            // A budget boundary may bisect a UTF-8 sequence; drop only the unfinished suffix.
            while (length > 0 && !validUtf8(Arrays.copyOf(raw, length))) length--;
            return new String(raw, 0, length, StandardCharsets.UTF_8);
        }
    }

    public DiffSummary diff() throws IOException {
        Map<String, FileSnapshot> current = capture(false);
        TreeSet<String> allPaths = new TreeSet<>();
        allPaths.addAll(baseline.keySet());
        allPaths.addAll(current.keySet());

        List<String> changedPaths = new ArrayList<>();
        int additions = 0;
        int deletions = 0;
        int lineStatsUnavailableFiles = 0;
        MessageDigest digest = sha256Digest();

        for (String path : allPaths) {
            FileSnapshot before = baseline.get(path);
            FileSnapshot after = current.get(path);
            if (sameContent(before, after)) {
                continue;
            }

            changedPaths.add(path);
            updateDigest(digest, path, before, after);
            byte[] beforeBytes = before == null ? new byte[0] : before.textBytes();
            byte[] afterBytes = readCurrentText(path, after);
            if (beforeBytes != null && afterBytes != null) {
                RawText beforeText = new RawText(beforeBytes);
                RawText afterText = new RawText(afterBytes);
                EditList edits = new HistogramDiff().diff(
                        RawTextComparator.DEFAULT,
                        beforeText,
                        afterText);
                for (Edit edit : edits) {
                    deletions += edit.getEndA() - edit.getBeginA();
                    additions += edit.getEndB() - edit.getBeginB();
                }
            } else {
                lineStatsUnavailableFiles++;
            }
        }

        return new DiffSummary(
                !changedPaths.isEmpty(),
                changedPaths.size(),
                additions,
                deletions,
                lineStatsUnavailableFiles,
                List.copyOf(changedPaths),
                HexFormat.of().formatHex(digest.digest()));
    }

    private Map<String, FileSnapshot> capture(boolean retainText) throws IOException {
        Map<String, FileSnapshot> files = new TreeMap<>();
        long[] remainingTextCache = {retainText ? MAX_BASELINE_TEXT_CACHE_BYTES : 0L};
        if (claims.workspaceWrite()) {
            collectScope(projectRoot, files, true, remainingTextCache);
            return files;
        }
        for (String claim : claims.writePaths()) {
            if (claim == null || claim.isBlank()) {
                continue;
            }
            String normalized = claim.endsWith("/")
                    ? claim.substring(0, claim.length() - 1)
                    : claim;
            Path scope = projectRoot.resolve(normalized).normalize();
            if (!scope.startsWith(projectRoot)) {
                continue;
            }
            ensureSafePath(scope);
            collectScope(scope, files, false, remainingTextCache);
        }
        return files;
    }

    private void collectScope(Path scope,
                              Map<String, FileSnapshot> files,
                              boolean applyExcludes,
                              long[] remainingTextCache) throws IOException {
        if (scope == null || !Files.exists(scope, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isRegularFile(scope, LinkOption.NOFOLLOW_LINKS)) {
            captureFile(scope, files, applyExcludes, remainingTextCache);
            return;
        }
        if (!Files.isDirectory(scope, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(scope, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (applyExcludes && !dir.equals(projectRoot) && isExcludedPath(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    captureFile(file, files, applyExcludes, remainingTextCache);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void captureFile(Path file,
                             Map<String, FileSnapshot> files,
                             boolean applyExcludes,
                             long[] remainingTextCache) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(projectRoot)) {
            return;
        }
        ensureSafePath(normalized);
        String relative = projectRoot.relativize(normalized).toString().replace('\\', '/');
        if (relative.isBlank() || (applyExcludes && isExcluded(relative))) {
            return;
        }

        long size = Files.size(normalized);
        byte[] textBytes = null;
        boolean textComparable = false;
        String hash;
        if (size <= MAX_TEXT_DIFF_BYTES) {
            byte[] bytes;
            try (InputStream input = Files.newInputStream(normalized)) {
                bytes = input.readNBytes(MAX_TEXT_DIFF_BYTES + 1);
            }
            if (bytes.length > MAX_TEXT_DIFF_BYTES) {
                files.put(relative, new FileSnapshot(sha256(normalized), Files.size(normalized), null, false));
                return;
            }
            hash = sha256(bytes);
            if (!looksBinary(bytes)) {
                textComparable = true;
                if (remainingTextCache[0] >= bytes.length) {
                    textBytes = bytes;
                    remainingTextCache[0] -= bytes.length;
                }
            }
        } else {
            hash = sha256(normalized);
        }
        files.put(relative, new FileSnapshot(hash, size, textBytes, textComparable));
    }

    private boolean isExcludedPath(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(projectRoot)) {
            return true;
        }
        return isExcluded(projectRoot.relativize(normalized).toString().replace('\\', '/'));
    }

    private boolean isExcluded(String relative) throws IOException {
        String normalized = relative.replace('\\', '/');
        for (ExcludeRule rule : excludeRules) {
            if (rule.matches(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static List<ExcludeRule> compileExcludeRules(List<String> excludes) throws IOException {
        List<ExcludeRule> rules = new ArrayList<>();
        for (String raw : excludes) {
            String pattern = raw == null ? "" : raw.trim().replace('\\', '/');
            while (pattern.startsWith("/")) {
                pattern = pattern.substring(1);
            }
            while (pattern.endsWith("/") && !pattern.isEmpty()) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            if (pattern.isEmpty()) {
                continue;
            }
            try {
                rules.add(new ExcludeRule(
                        pattern,
                        pattern.contains("/"),
                        containsGlob(pattern)
                                ? FileSystems.getDefault().getPathMatcher("glob:" + pattern)
                                : null));
            } catch (IllegalArgumentException e) {
                throw new IOException("invalid snapshot exclude pattern: " + pattern, e);
            }
        }
        return List.copyOf(rules);
    }

    private static boolean containsGlob(String pattern) {
        return pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0
                || pattern.indexOf('[') >= 0 || pattern.indexOf('{') >= 0;
    }

    private static boolean sameContent(FileSnapshot before, FileSnapshot after) {
        if (before == null || after == null) {
            return before == after;
        }
        return before.sha256().equals(after.sha256());
    }

    private byte[] readCurrentText(String relative, FileSnapshot snapshot) throws IOException {
        if (snapshot == null) {
            return new byte[0];
        }
        if (!snapshot.textComparable() || snapshot.size() > MAX_TEXT_DIFF_BYTES) {
            return null;
        }
        Path file = projectRoot.resolve(relative).normalize();
        ensureSafePath(file);
        if (!file.startsWith(projectRoot) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_TEXT_DIFF_BYTES + 1);
        }
        if (bytes.length > MAX_TEXT_DIFF_BYTES) throw new IOException("workspace file grew while collecting diff");
        if (!sha256(bytes).equals(snapshot.sha256())) {
            throw new IOException("workspace file changed while collecting diff: " + relative);
        }
        return looksBinary(bytes) ? null : bytes;
    }

    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8_000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void updateDigest(MessageDigest digest,
                                     String path,
                                     FileSnapshot before,
                                     FileSnapshot after) {
        String value = path + '\0'
                + (before == null ? "<missing>" : before.sha256()) + '\0'
                + (after == null ? "<missing>" : after.sha256()) + '\n';
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, FileSnapshot> restoreBaseline(Baseline persistedBaseline) throws IOException {
        if (persistedBaseline == null) {
            throw new IOException("persisted diff baseline is missing");
        }
        Map<String, FileSnapshot> restored = new TreeMap<>();
        for (Map.Entry<String, String> entry : persistedBaseline.hashes().entrySet()) {
            String relative = entry.getKey() == null ? "" : entry.getKey().replace('\\', '/');
            Path resolved;
            try {
                resolved = projectRoot.resolve(relative).normalize();
                if (relative.isBlank() || Path.of(relative).isAbsolute() || !resolved.startsWith(projectRoot)) {
                    throw new IOException("persisted diff baseline contains an invalid path");
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("persisted diff baseline contains an invalid path", e);
            }
            String hash = entry.getValue() == null ? "" : entry.getValue();
            if (!inWriteScope(resolved) || !relative.equals(projectRoot.relativize(resolved).toString().replace('\\', '/'))
                    || (claims.workspaceWrite() && isExcluded(relative))) {
                throw new IOException("persisted diff baseline exceeds task write scope");
            }
            if (!hash.matches("[0-9a-fA-F]{64}")) {
                throw new IOException("persisted diff baseline contains an invalid hash");
            }
            restored.put(relative, new FileSnapshot(hash.toLowerCase(), -1L, null, false));
        }
        return restored;
    }

    private boolean inWriteScope(Path file) {
        if (claims.workspaceWrite()) return true;
        for (String claim : claims.writePaths()) {
            Path scope = projectRoot.resolve(claim).normalize();
            if (scope.startsWith(projectRoot) && file.startsWith(scope)) return true;
        }
        return false;
    }

    private void ensureSafePath(Path file) throws IOException {
        if (!file.startsWith(projectRoot)) throw new IOException("diff path escapes workspace");
        for (Path cursor = file; cursor != null && !cursor.equals(projectRoot); cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) throw new IOException("diff path contains a symbolic link");
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !file.toRealPath().startsWith(projectRoot.toRealPath())) {
            throw new IOException("diff path escapes real workspace");
        }
    }

    private record FileSnapshot(String sha256, long size, byte[] textBytes, boolean textComparable) {
        private FileSnapshot {
            textBytes = textBytes == null ? null : Arrays.copyOf(textBytes, textBytes.length);
        }

        @Override
        public byte[] textBytes() {
            return textBytes == null ? null : Arrays.copyOf(textBytes, textBytes.length);
        }
    }

    private record ExcludeRule(String pattern, boolean pathPattern, PathMatcher matcher) {
        private boolean matches(String normalized) {
            if (!pathPattern) {
                for (String component : normalized.split("/")) {
                    if (component.equals(pattern)
                            || (matcher != null && matcher.matches(Path.of(component)))) {
                        return true;
                    }
                }
                return false;
            }
            if (normalized.equals(pattern) || normalized.startsWith(pattern + "/")) {
                return true;
            }
            if (pattern.endsWith("/**")) {
                String subtree = pattern.substring(0, pattern.length() - 3);
                if (normalized.equals(subtree) || normalized.startsWith(subtree + "/")) {
                    return true;
                }
            }
            return matcher != null && matcher.matches(Path.of(normalized));
        }
    }

    public record Baseline(Map<String, String> hashes, String snapshotCommitId) {
        public Baseline(Map<String, String> hashes) { this(hashes, ""); }
        public Baseline {
            hashes = hashes == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new TreeMap<>(hashes));
            snapshotCommitId = snapshotCommitId == null ? "" : snapshotCommitId;
            if (!snapshotCommitId.isEmpty() && !snapshotCommitId.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("invalid task snapshot reference");
            }
        }
    }

    public record DiffSummary(boolean changed,
                              int changedFiles,
                              int additions,
                              int deletions,
                              int lineStatsUnavailableFiles,
                              List<String> changedPaths,
                              String digest) {
        public DiffSummary {
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
            digest = digest == null ? "" : digest;
        }
    }
}
