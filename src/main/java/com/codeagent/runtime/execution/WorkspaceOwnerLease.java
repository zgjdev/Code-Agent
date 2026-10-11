package com.codeagent.runtime.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Properties;

/** OS ownership plus a catalog protected overlap check; metadata never proves liveness. */
public final class WorkspaceOwnerLease implements AutoCloseable {
    private final LocalResourceLock lock;
    private final WorkspaceIdentity identity;
    private final String ownerId;
    private final Object fileKey;

    private WorkspaceOwnerLease(LocalResourceLock lock, WorkspaceIdentity identity, String ownerId, Object fileKey) {
        this.lock = lock;
        this.identity = identity;
        this.ownerId = ownerId;
        this.fileKey = fileKey;
    }

    public static WorkspaceOwnerLease acquire(Path locksDirectory, WorkspaceIdentity identity, String ownerId)
            throws IOException {
        Objects.requireNonNull(identity, "identity");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId must not be blank");
        Files.createDirectories(locksDirectory);
        String type = Files.getFileStore(locksDirectory).type().toLowerCase(java.util.Locale.ROOT);
        if (type.contains("nfs") || type.contains("smb") || type.contains("cifs")) {
            throw new IOException("Ownership requires a local lock filesystem");
        }
        Path root = identity.canonicalRoot().toRealPath();
        if (!root.equals(identity.canonicalRoot()) || !Files.isDirectory(root)) {
            throw new IOException("Workspace identity has changed");
        }
        Object fileKey = directoryIdentity(root);
        try (LocalResourceLock catalog = acquireCatalog(locksDirectory.resolve("catalog.lock"))) {
            Path catalogPath = locksDirectory.resolve("workspaces.properties");
            Properties entries = new Properties();
            if (Files.exists(catalogPath)) {
                try (var input = Files.newInputStream(catalogPath)) { entries.load(input); }
            }
            for (String key : entries.stringPropertyNames()) {
                Path other = Path.of(entries.getProperty(key));
                boolean overlap = root.startsWith(other) || other.startsWith(root)
                        || (Files.exists(other) && Files.isSameFile(root, other));
                if (!overlap) continue;
                var probe = LocalResourceLock.tryAcquire(lockPath(locksDirectory, key));
                if (probe.isEmpty()) throw new WorkspaceAlreadyOwnedException(identity.key());
                probe.orElseThrow().close();
            }
            LocalResourceLock owner = LocalResourceLock.tryAcquire(lockPath(locksDirectory, identity.key().value()))
                    .orElseThrow(() -> new WorkspaceAlreadyOwnedException(identity.key()));
            try {
                entries.setProperty(identity.key().value(), root.toString());
                Path temporary = Files.createTempFile(locksDirectory, "catalog-", ".tmp");
                try {
                    try (var output = Files.newOutputStream(temporary)) { entries.store(output, "Workspace roots"); }
                    Files.move(temporary, catalogPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
                return new WorkspaceOwnerLease(owner, identity, ownerId, fileKey);
            } catch (IOException | RuntimeException failure) {
                owner.close();
                throw failure;
            }
        }
    }

    private static Path lockPath(Path directory, String key) {
        return directory.resolve("workspace_" + SharedExecutionResourceLeases.hash(key) + ".lock");
    }

    private static LocalResourceLock acquireCatalog(Path path) throws IOException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            var lock = LocalResourceLock.tryAcquire(path);
            if (lock.isPresent()) return lock.orElseThrow();
            try { Thread.sleep(10); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Registration interrupted", e); }
        } while (System.nanoTime() < deadline);
        throw new IOException("Workspace registration catalog is busy");
    }

    public WorkspaceIdentity identity() { return identity; }
    public String ownerId() { return ownerId; }
    public boolean isValid() { return lock.valid(); }
    public void verifyIdentity() throws IOException {
        Path current = identity.canonicalRoot().toRealPath();
        if (!isValid() || !current.equals(identity.canonicalRoot())
                || !Objects.equals(fileKey, directoryIdentity(current))) {
            throw new IOException("Registered workspace identity changed or ownership ended");
        }
    }
    private static Object directoryIdentity(Path root) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class);
        // Windows' Java provider exposes no fileKey; NTFS creation time is stable across normal edits.
        return attributes.fileKey() != null ? attributes.fileKey()
                : Files.getFileStore(root).name() + ":" + attributes.creationTime();
    }
    @Override public void close() throws IOException { lock.close(); }

    public static final class WorkspaceAlreadyOwnedException extends IOException {
        public WorkspaceAlreadyOwnedException(WorkspaceKey key) {
            super("Workspace or overlapping root is already owned: " + key.value());
        }
    }
}
