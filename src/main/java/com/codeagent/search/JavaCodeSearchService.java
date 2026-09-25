package com.codeagent.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class JavaCodeSearchService implements CodeSearchService {
    private static final long MAX_SEARCH_FILE_BYTES = 2L * 1024 * 1024;
    private final Set<String> excludedDirs;

    public JavaCodeSearchService(Set<String> excludedDirs) {
        this.excludedDirs = Set.copyOf(excludedDirs);
    }

    @Override
    public CodeSearchResult search(CodeSearchRequest request) {
        Pattern pattern;
        try {
            int flags = request.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            pattern = Pattern.compile(request.regex() ? request.query() : Pattern.quote(request.query()), flags);
        } catch (PatternSyntaxException e) {
            return new CodeSearchResult("java", List.of(), false, "正则表达式无效");
        }
        List<Path> candidates = collectFiles(request);
        List<GrepMatch> matches = new ArrayList<>();
        Map<String, Integer> perFile = new HashMap<>();
        boolean headLimited = false;
        for (Path file : candidates) {
            try {
                if (Files.size(file) > MAX_SEARCH_FILE_BYTES || binary(file)) continue;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                String key = portable(request.projectRoot().relativize(file));
                for (int i = 0; i < lines.size(); i++) {
                    if (!pattern.matcher(lines.get(i)).find()) continue;
                    int count = perFile.getOrDefault(key, 0);
                    if (count >= request.headLimit()) { headLimited = true; break; }
                    if (matches.size() >= request.maxResults()) break;
                    int from = Math.max(0, i - request.contextLines());
                    int to = Math.min(lines.size() - 1, i + request.contextLines());
                    List<ContextLine> context = new ArrayList<>();
                    for (int line = from; line <= to; line++) {
                        context.add(new ContextLine(line + 1, lines.get(line)));
                    }
                    matches.add(new GrepMatch(key, i + 1, context));
                    perFile.put(key, count + 1);
                }
                if (matches.size() >= request.maxResults()) break;
            } catch (IOException ignored) {
                // A changing/unreadable file is isolated from the remaining live search.
            }
        }
        boolean partial = matches.size() >= request.maxResults() || headLimited;
        String reason = matches.size() >= request.maxResults()
                ? "已达到 max_results=" + request.maxResults()
                : headLimited ? "部分文件已达到 head_limit=" + request.headLimit() : "";
        return new CodeSearchResult("java", matches, partial, reason);
    }

    private List<Path> collectFiles(CodeSearchRequest request) {
        List<Path> files = new ArrayList<>();
        PathMatcher pathMatcher = matcher(request.projectRoot(), normalizeGlob(request.glob()));
        PathMatcher nameMatcher = matcher(request.projectRoot(), fileGlob(request.glob()));
        try {
            Files.walkFileTree(request.root(), new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return !dir.equals(request.projectRoot()) && excludedDirs.contains(name)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        Path relative = request.projectRoot().relativize(file);
                        if (pathMatcher == null || pathMatcher.matches(relative)
                                || nameMatcher.matches(file.getFileName())) files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        files.sort(Comparator.comparing(path -> portable(request.projectRoot().relativize(path))));
        return files;
    }

    private static PathMatcher matcher(Path root, String glob) {
        return glob == null ? null : root.getFileSystem().getPathMatcher("glob:" + glob);
    }

    private static String normalizeGlob(String pattern) {
        if (pattern == null || pattern.isBlank()) return null;
        String value = pattern.replace('\\', '/').trim();
        return !value.contains("/") && !value.startsWith("**") ? "**/" + value : value;
    }

    private static String fileGlob(String pattern) {
        if (pattern == null || pattern.isBlank()) return "*";
        String value = pattern.replace('\\', '/').trim();
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static boolean binary(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        for (int i = 0; i < Math.min(bytes.length, 4096); i++) if (bytes[i] == 0) return true;
        return false;
    }

    private static String portable(Path path) { return path.toString().replace('\\', '/'); }
}
