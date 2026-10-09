package com.codeagent.rag;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * One stable, admitted content read used by hashing, chunking and parsing.
 */
public record FileContentSnapshot(Path absolutePath, Path relativePath, String content, String contentHash, long sizeBytes, long modifiedMillis, String language) {

    public static FileContentSnapshot read(IndexPathPolicy policy, Path requested) throws IOException {
        Path path = policy.resolve(requested);
        if (!policy.isEligible(path))
            throw new IOException("Path is not eligible for indexing");
        IndexRestrictedReader.Content read = IndexRestrictedReader.read(policy.root(), path);
        byte[] bytes = read.bytes();
        BasicFileAttributes after = read.attributes();
        if (!policy.isEligible(path)) throw new IOException("Path changed during snapshot");
        String name = path.getFileName().toString();
        try {
            return new FileContentSnapshot(path, policy.root().relativize(path), StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length, after.lastModifiedTime().toMillis(), name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean stillCurrent(IndexPathPolicy policy) throws IOException {
        return contentHash.equals(read(policy, absolutePath).contentHash);
    }
}
