package com.codeagent.runtime.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Cross-host resource leases, acquired in a stable order without blocking a worker. */
public final class SharedExecutionResourceLeases {
    private final Path directory;
    public SharedExecutionResourceLeases(Path directory) { this.directory = directory; }

    public Optional<Lease> tryAcquire(Set<String> resources) throws IOException {
        List<LocalResourceLock> acquired = new ArrayList<>();
        try {
            for (String resource : resources.stream().sorted().toList()) {
                if (resource == null || resource.isBlank()) throw new IllegalArgumentException("Blank resource key");
                var lock = LocalResourceLock.tryAcquire(directory.resolve("resource_" + hash(resource) + ".lock"));
                if (lock.isEmpty()) {
                    new Lease(acquired).close();
                    return Optional.empty();
                }
                acquired.add(lock.orElseThrow());
            }
            return Optional.of(new Lease(acquired));
        } catch (IOException | RuntimeException failure) {
            try { new Lease(acquired).close(); }
            catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class Lease implements AutoCloseable {
        private final List<LocalResourceLock> locks;
        private boolean closed;
        private Lease(List<LocalResourceLock> locks) { this.locks = List.copyOf(locks); }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            IOException failure = null;
            for (int i = locks.size() - 1; i >= 0; i--) {
                try { locks.get(i).close(); }
                catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            closed = true;
            if (failure != null) throw failure;
        }
    }
}
