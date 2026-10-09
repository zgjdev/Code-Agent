package com.codeagent.rag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Restricted reads use anchored directory handles when supported; portable reads revalidate before consuming. */
final class IndexRestrictedReader {
    private IndexRestrictedReader() {}

    record Content(byte[] bytes, BasicFileAttributes attributes) {}

    static Content read(Path root, Path file) throws IOException {
        return read(root, file, () -> {});
    }

    // Deterministic test seam after opening, before consuming any source bytes.
    static Content read(Path root, Path file, Runnable afterOpen) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Path canonical = root.toAbsolutePath().normalize();
        List<Identity> ancestors = identities(canonical, absolute);
        BasicFileAttributes before = attributes(absolute);
        if (!before.isRegularFile()) throw new IOException("Not a regular index file");
        // Anchor secure traversal at the filesystem root so replacement of the workspace root cannot redirect opens.
        Path anchor = canonical.getRoot();
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(anchor)) {
            if (directory instanceof SecureDirectoryStream<Path> secure) {
                return secureRead(secure, anchor.relativize(absolute), ancestors, absolute, before, afterOpen);
            }
            try (SeekableByteChannel channel = Files.newByteChannel(absolute,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                afterOpen.run();
                verify(ancestors, absolute, before);
                byte[] bytes = consume(channel);
                verify(ancestors, absolute, before);
                return new Content(bytes, before);
            }
        }
    }

    private static Content secureRead(SecureDirectoryStream<Path> directory, Path relative,
            List<Identity> ancestors, Path absolute, BasicFileAttributes before, Runnable afterOpen) throws IOException {
        if (relative.getNameCount() > 1) {
            try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(relative.getName(0), LinkOption.NOFOLLOW_LINKS)) {
                return secureRead(child, relative.subpath(1, relative.getNameCount()), ancestors, absolute, before, afterOpen);
            }
        }
        Path name = relative.getFileName();
        BasicFileAttributeView view = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        BasicFileAttributes anchoredBefore = view.readAttributes();
        same(before, anchoredBefore);
        try (SeekableByteChannel channel = directory.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            afterOpen.run();
            same(anchoredBefore, view.readAttributes());
            verify(ancestors, absolute, before);
            byte[] bytes = consume(channel);
            same(anchoredBefore, view.readAttributes());
            verify(ancestors, absolute, before);
            return new Content(bytes, before);
        }
    }

    private static byte[] consume(SeekableByteChannel channel) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                output.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    private static List<Identity> identities(Path root, Path file) throws IOException {
        if (!file.startsWith(root) || file.equals(root)) throw new IOException("File outside index root");
        List<Identity> result = new ArrayList<>();
        Path cursor = file.getRoot();
        result.add(identity(cursor));
        Path relative = cursor.relativize(file);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            cursor = cursor.resolve(relative.getName(i));
            result.add(identity(cursor));
        }
        return result;
    }

    private static Identity identity(Path path) throws IOException {
        BasicFileAttributes attrs = attributes(path);
        if (!attrs.isDirectory() || !path.toRealPath().equals(path.toAbsolutePath().normalize()))
            throw new IOException("Unsafe index ancestor");
        return new Identity(path, attrs.fileKey());
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || Files.isSymbolicLink(path)) throw new IOException("Index links are not allowed");
        return attrs;
    }

    private static void verify(List<Identity> ancestors, Path file, BasicFileAttributes before) throws IOException {
        for (Identity expected : ancestors) {
            Identity current = identity(expected.path());
            if (!Objects.equals(expected.key(), current.key())) throw new IOException("Index ancestor changed");
        }
        same(before, attributes(file));
    }

    private static void same(BasicFileAttributes before, BasicFileAttributes after) throws IOException {
        if (!after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("Index file changed during read");
    }

    private record Identity(Path path, Object key) {}
}
