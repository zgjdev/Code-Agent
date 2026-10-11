package com.codeagent.runtime.execution;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** One channel per held file in this JVM; never close a second channel on an owned POSIX lock. */
final class LocalResourceLock implements AutoCloseable {
    private static final Map<Path, LocalResourceLock> HELD = new HashMap<>();
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private volatile boolean closed;

    private LocalResourceLock(Path path, FileChannel channel, FileLock lock) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
    }

    static synchronized Optional<LocalResourceLock> tryAcquire(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path canonical = path.toAbsolutePath().getParent().toRealPath().resolve(path.getFileName());
        if (HELD.containsKey(canonical)) return Optional.empty();
        FileChannel channel = FileChannel.open(canonical,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException held) { lock = null; }
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            LocalResourceLock result = new LocalResourceLock(canonical, channel, lock);
            HELD.put(canonical, result);
            return Optional.of(result);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    boolean valid() { return !closed && lock.isValid(); }

    @Override public void close() throws IOException {
        synchronized (LocalResourceLock.class) {
            if (closed) return;
            try { lock.release(); }
            finally {
                channel.close();
                closed = true;
                HELD.remove(path, this);
            }
        }
    }
}
