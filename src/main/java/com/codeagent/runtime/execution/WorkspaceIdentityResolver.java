package com.codeagent.runtime.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class WorkspaceIdentityResolver {
    public WorkspaceIdentity resolve(Path root) throws IOException {
        if (root == null) {
            throw new IllegalArgumentException("workspace root must not be null");
        }
        Path canonicalRoot = root.toRealPath();
        if (!Files.isDirectory(canonicalRoot)) {
            throw new IOException("Workspace root is not a directory: " + root);
        }
        return new WorkspaceIdentity(keyFor(canonicalRoot), canonicalRoot);
    }

    private static WorkspaceKey keyFor(Path canonicalRoot) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRoot.toString().getBytes(StandardCharsets.UTF_8));
            return new WorkspaceKey("ws_" + HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
