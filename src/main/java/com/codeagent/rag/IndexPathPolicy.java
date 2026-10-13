package com.codeagent.rag;

import org.eclipse.jgit.ignore.IgnoreNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Shared admission policy. Never follows a symbolic link, including ancestor links.
 */
public final class IndexPathPolicy {

    private static final Set<String> EXCLUDED = Set.of(".git", ".idea", ".vscode", ".codeagent", "node_modules", "target", "build", "dist", "out", ".gradle", ".mvn");

    private static final Set<String> EXTENSIONS = Set.of("java", "py", "js", "jsx", "ts", "tsx", "go", "rs", "c", "cc", "cpp", "h", "hpp", "md", "xml", "properties", "yaml", "yml", "json", "sh", "gradle", "kt", "kts", "sql", "toml");

    private final Path root;

    private final IgnoreNode ignore = new IgnoreNode();

    public IndexPathPolicy(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid project root");
        this.root = root.toRealPath();
        if (!this.root.equals(root.toAbsolutePath().normalize())) throw new IOException("Linked project ancestors are not allowed");
        Path file = this.root.resolve(".gitignore");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unable to inspect gitignore");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && (!Files.isReadable(file) || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Unsafe gitignore");
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
            try (InputStream input = new java.io.ByteArrayInputStream(IndexRestrictedReader.read(this.root, file).bytes())) {
                ignore.parse(".gitignore", input);
            }
    }

    public Path root() {
        return root;
    }

    public boolean isEligibleDirectory(Path path) {
        return admitted(path, true);
    }

    public boolean isEligible(Path path) {
        return admitted(path, false) && Files.isRegularFile(resolve(path), LinkOption.NOFOLLOW_LINKS) && supported(resolve(path));
    }

    public Path resolve(Path path) {
        return (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
    }

    private boolean admitted(Path path, boolean directory) {
        Path absolute = resolve(path);
        if (Files.isSymbolicLink(root) || !absolute.startsWith(root))
            return false;
        Path relative = root.relativize(absolute);
        Path cursor = root;
        for (int i = 0; i < relative.getNameCount(); i++) {
            cursor = cursor.resolve(relative.getName(i));
            boolean dir = i < relative.getNameCount() - 1 || directory;
            String name = relative.getName(i).toString().toLowerCase(Locale.ROOT);
            try {
                if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().equals(cursor.toAbsolutePath().normalize())) return false;
            } catch (IOException e) { return false; }
            if (Files.isSymbolicLink(cursor) || dir && EXCLUDED.contains(name) || !dir && sensitive(name))
                return false;
            if (Boolean.TRUE.equals(ignore.checkIgnored(root.relativize(cursor).toString().replace('\\', '/'), dir)))
                return false;
        }
        return true;
    }

    private static boolean sensitive(String name) {
        return name.equals(".env") || name.startsWith(".env.") || name.endsWith(".pem") || name.endsWith(".key") || name.equals("credentials.json") || name.equals("secrets.json") || name.endsWith(".jsonl");
    }

    private static boolean supported(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
