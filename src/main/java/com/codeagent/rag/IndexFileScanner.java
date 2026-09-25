package com.codeagent.rag;

import org.eclipse.jgit.ignore.IgnoreNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class IndexFileScanner {
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", ".codeagent", "node_modules", "target",
            "build", "dist", "out", ".gradle", ".mvn");
    private static final Set<String> EXTENSIONS = Set.of(
            "java", "py", "js", "jsx", "ts", "tsx", "go", "rs", "c", "cc", "cpp",
            "h", "hpp", "md", "xml", "properties", "yaml", "yml", "json", "sh",
            "gradle", "kt", "kts", "sql", "toml");

    public ScanResult scan(Path requestedRoot) {
        List<ScannedFile> files = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (requestedRoot == null || !Files.isDirectory(requestedRoot, LinkOption.NOFOLLOW_LINKS)) {
            return new ScanResult(List.of(), false, List.of("invalid_project_root"));
        }
        try {
            Path root = requestedRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            IgnoreNode ignore = loadIgnore(root);
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root)) {
                        String name = dir.getFileName().toString();
                        Path relative = root.relativize(dir);
                        if (attrs.isSymbolicLink() || EXCLUDED_DIRECTORIES.contains(name)
                                || ignored(ignore, relative, true)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = root.relativize(file);
                    try {
                        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || sensitive(file)
                                || ignored(ignore, relative, false) || !supported(file)) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
                        if (!real.startsWith(root)) return FileVisitResult.CONTINUE;
                        byte[] content = Files.readAllBytes(real);
                        files.add(new ScannedFile(real, relative, sha256(content), attrs.size(),
                                attrs.lastModifiedTime().toMillis(), language(file)));
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

    private static IgnoreNode loadIgnore(Path root) throws IOException {
        IgnoreNode node = new IgnoreNode();
        Path file = root.resolve(".gitignore");
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            try (InputStream input = Files.newInputStream(file)) {
                node.parse(".gitignore", input);
            }
        }
        return node;
    }

    private static boolean ignored(IgnoreNode node, Path relative, boolean directory) {
        Boolean result = node.checkIgnored(relative.toString().replace('\\', '/'), directory);
        return Boolean.TRUE.equals(result);
    }

    private static boolean sensitive(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals(".env") || name.startsWith(".env.") || name.endsWith(".pem")
                || name.endsWith(".key") || name.equals("credentials.json")
                || name.equals("secrets.json") || name.endsWith(".jsonl");
    }

    private static boolean supported(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String language(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "text" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
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
