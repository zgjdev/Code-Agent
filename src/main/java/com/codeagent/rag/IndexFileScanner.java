package com.codeagent.rag;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class IndexFileScanner {
    public ScanResult scan(Path requestedRoot) {
        List<ScannedFile> files = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (requestedRoot == null || !Files.isDirectory(requestedRoot, LinkOption.NOFOLLOW_LINKS)) {
            return new ScanResult(List.of(), false, List.of("invalid_project_root"));
        }
        try {
            Path root = requestedRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            IndexPathPolicy policy = new IndexPathPolicy(root);
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root)) {
                        if (!policy.isEligibleDirectory(dir)) return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = root.relativize(file);
                    try {
                        if (!policy.isEligible(file)) return FileVisitResult.CONTINUE;
                        FileContentSnapshot snapshot = FileContentSnapshot.read(policy, file);
                        files.add(new ScannedFile(snapshot.absolutePath(), snapshot.relativePath(), snapshot.contentHash(),
                                snapshot.sizeBytes(), snapshot.modifiedMillis(), snapshot.language()));
                    } catch (IOException | RuntimeException e) {
                        failures.add(relative.toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    failures.add(root.relativize(file).toString().replace('\\', '/'));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return new ScanResult(List.copyOf(files), false, List.of("scan_failed"));
        }
        files.sort(Comparator.comparing(file -> file.relativePath().toString().replace('\\', '/')));
        return new ScanResult(List.copyOf(files), failures.isEmpty(), List.copyOf(failures));
    }

    public record ScannedFile(Path absolutePath, Path relativePath, String contentHash,
                              long sizeBytes, long modifiedMillis, String language) {}

    public record ScanResult(List<ScannedFile> files, boolean complete, List<String> failures) {
        public ScanResult {
            files = List.copyOf(files);
            failures = List.copyOf(failures);
        }
    }
}
