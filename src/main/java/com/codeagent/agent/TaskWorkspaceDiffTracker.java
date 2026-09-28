package com.codeagent.agent;

import com.codeagent.plan.TaskResourceClaims;
import com.codeagent.snapshot.SnapshotConfig;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
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

    private final Path projectRoot;
    private final TaskResourceClaims claims;
    private final List<String> excludes;
    private final Map<String, FileSnapshot> baseline;

    private TaskWorkspaceDiffTracker(Path projectRoot, TaskResourceClaims claims) throws IOException {
        Path root = projectRoot == null ? Path.of(".") : projectRoot;
        this.projectRoot = root.toAbsolutePath().normalize();
        this.claims = claims == null
                ? new TaskResourceClaims(List.of(), List.of(), false)
                : claims;
        this.excludes = SnapshotConfig.fromEnvironment().excludes();
        this.baseline = capture();
    }

    public static TaskWorkspaceDiffTracker start(Path projectRoot, TaskResourceClaims claims) throws IOException {
        return new TaskWorkspaceDiffTracker(projectRoot, claims);
    }

    public DiffSummary diff() throws IOException {
        Map<String, FileSnapshot> current = capture();
        TreeSet<String> allPaths = new TreeSet<>();
        allPaths.addAll(baseline.keySet());
        allPaths.addAll(current.keySet());

        List<String> changedPaths = new ArrayList<>();
        int additions = 0;
        int deletions = 0;
        int nonTextFiles = 0;
        MessageDigest digest = sha256Digest();

        for (String path : allPaths) {
            FileSnapshot before = baseline.get(path);
            FileSnapshot after = current.get(path);
            if (sameContent(before, after)) {
                continue;
            }

            changedPaths.add(path);
            updateDigest(digest, path, before, after);
            if (isTextComparable(before) && isTextComparable(after)) {
                RawText beforeText = new RawText(before == null ? new byte[0] : before.textBytes());
                RawText afterText = new RawText(after == null ? new byte[0] : after.textBytes());
                EditList edits = new HistogramDiff().diff(
                        RawTextComparator.DEFAULT,
                        beforeText,
                        afterText);
                for (Edit edit : edits) {
                    deletions += edit.getEndA() - edit.getBeginA();
                    additions += edit.getEndB() - edit.getBeginB();
                }
            } else {
                nonTextFiles++;
            }
        }

        return new DiffSummary(
                !changedPaths.isEmpty(),
                changedPaths.size(),
                additions,
                deletions,
                nonTextFiles,
                List.copyOf(changedPaths),
                HexFormat.of().formatHex(digest.digest()));
    }

    private Map<String, FileSnapshot> capture() throws IOException {
        Map<String, FileSnapshot> files = new TreeMap<>();
        if (claims.workspaceWrite()) {
            collectScope(projectRoot, files, true);
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
            collectScope(scope, files, false);
        }
        return files;
    }

    private void collectScope(Path scope,
                              Map<String, FileSnapshot> files,
                              boolean applyExcludes) throws IOException {
        if (scope == null || !Files.exists(scope, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isRegularFile(scope, LinkOption.NOFOLLOW_LINKS)) {
            captureFile(scope, files, applyExcludes);
            return;
        }
        if (!Files.isDirectory(scope, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(scope)) {
            for (Path path : paths
                    .filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    .toList()) {
                captureFile(path, files, applyExcludes);
            }
        }
    }

    private void captureFile(Path file,
                             Map<String, FileSnapshot> files,
                             boolean applyExcludes) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(projectRoot)) {
            return;
        }
        String relative = projectRoot.relativize(normalized).toString().replace('\\', '/');
        if (relative.isBlank() || (applyExcludes && isExcluded(relative))) {
            return;
        }

        long size = Files.size(normalized);
        byte[] textBytes = null;
        boolean textComparable = false;
        String hash;
        if (size <= MAX_TEXT_DIFF_BYTES) {
            byte[] bytes = Files.readAllBytes(normalized);
            hash = sha256(bytes);
            if (!looksBinary(bytes)) {
                textBytes = bytes;
                textComparable = true;
            }
        } else {
            hash = sha256(normalized);
        }
        files.put(relative, new FileSnapshot(hash, textBytes, textComparable));
    }

    private boolean isExcluded(String relative) {
        String normalized = relative.replace('\\', '/');
        for (String raw : excludes) {
            String pattern = raw == null ? "" : raw.trim().replace('\\', '/');
            if (pattern.isEmpty()) {
                continue;
            }
            if (pattern.endsWith("/")) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            if (normalized.equals(pattern) || normalized.startsWith(pattern + "/")) {
                return true;
            }
            if (pattern.contains("*")) {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                Path fileName = Path.of(normalized).getFileName();
                if (fileName != null && matcher.matches(fileName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameContent(FileSnapshot before, FileSnapshot after) {
        if (before == null || after == null) {
            return before == after;
        }
        return before.sha256().equals(after.sha256());
    }

    private static boolean isTextComparable(FileSnapshot snapshot) {
        return snapshot == null || snapshot.textComparable();
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

    private record FileSnapshot(String sha256, byte[] textBytes, boolean textComparable) {
        private FileSnapshot {
            textBytes = textBytes == null ? null : Arrays.copyOf(textBytes, textBytes.length);
        }

        @Override
        public byte[] textBytes() {
            return textBytes == null ? null : Arrays.copyOf(textBytes, textBytes.length);
        }
    }

    public record DiffSummary(boolean changed,
                              int changedFiles,
                              int additions,
                              int deletions,
                              int nonTextFiles,
                              List<String> changedPaths,
                              String digest) {
        public DiffSummary {
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
            digest = digest == null ? "" : digest;
        }
    }
}
