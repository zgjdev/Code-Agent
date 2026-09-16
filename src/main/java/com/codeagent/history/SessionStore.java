package com.codeagent.history;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable append-only storage for resumable conversation sessions. */
public final class SessionStore implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path sessionsDirectory;
    private final SessionReplayer replayer = new SessionReplayer();

    private SessionStore(Path historyDirectory) throws IOException {
        this.sessionsDirectory = historyDirectory.toAbsolutePath().normalize().resolve("sessions");
        createSecureDirectory(sessionsDirectory);
    }

    public static SessionStore open(Path historyDirectory) throws IOException {
        return new SessionStore(Objects.requireNonNull(historyDirectory, "historyDirectory"));
    }

    public SessionHandle create(SessionCreateRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        Path workspace = normalizeWorkspace(request.workspace());
        String sessionId = generateSessionId();
        Path directory = sessionsDirectory.resolve(sessionId);
        createSecureDirectory(directory);
        createSecureDirectory(directory.resolve("attachments"));
        createSecureDirectory(directory.resolve("checkpoints"));
        createSecureFile(directory.resolve("events.jsonl"));
        createSecureFile(directory.resolve("session.lock"));

        long now = System.currentTimeMillis();
        SessionManifest manifest = new SessionManifest(
                SessionEvent.CURRENT_SCHEMA_VERSION, sessionId, workspace.toString(),
                normalize(request.provider()), normalize(request.model()), now, now,
                normalize(request.parentSessionId()), false, -1, false, null);
        writeManifest(directory, manifest);

        SessionHandle handle = openHandle(directory, manifest, List.of(), null);
        ObjectNode payload = JSON.createObjectNode();
        payload.put("workspace", workspace.toString());
        payload.put("provider", normalize(request.provider()));
        payload.put("model", normalize(request.model()));
        if (request.parentSessionId() != null) {
            payload.put("parentSessionId", request.parentSessionId());
        }
        handle.append(new SessionEventDraft(SessionEvent.Types.SESSION_START,
                request.mode(), request.actor(), "session-store", false,
                SessionEvent.SurfaceOperation.none(), payload));
        return handle;
    }

    public SessionHandle resumeWritable(String sessionId, Path workspace) throws IOException {
        String safeId = requireSafeSessionId(sessionId);
        Path directory = sessionsDirectory.resolve(safeId);
        SessionManifest manifest = readManifest(directory);
        Path expectedWorkspace = normalizeWorkspace(workspace);
        if (!expectedWorkspace.toString().equals(manifest.workspace())) {
            throw new WorkspaceMismatchException(
                    "session workspace does not match: expected " + manifest.workspace()
                            + " but got " + expectedWorkspace);
        }
        SessionHandle handle = openHandle(
                directory, manifest, null, "incomplete final line ignored during recovery");
        try {
            handle.recoverInterruptedState();
            return handle;
        } catch (Exception e) {
            handle.close();
            throw e;
        }
    }

    public Optional<SessionSummary> latestUnclosed(Path workspace) throws IOException {
        return list(workspace, Integer.MAX_VALUE).stream().filter(summary -> !summary.closed()).findFirst();
    }

    public Optional<SessionSummary> latest(Path workspace) throws IOException {
        return list(workspace, 1).stream().findFirst();
    }

    public List<SessionSummary> list(Path workspace, int limit) throws IOException {
        if (limit <= 0 || Files.notExists(sessionsDirectory)) {
            return List.of();
        }
        String expectedWorkspace = normalizeWorkspace(workspace).toString();
        List<SessionSummary> summaries = new ArrayList<>();
        try (var directories = Files.list(sessionsDirectory)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                Path manifestFile = directory.resolve("manifest.json");
                if (Files.notExists(manifestFile)) {
                    continue;
                }
                try {
                    SessionManifest manifest = JSON.readValue(manifestFile.toFile(), SessionManifest.class);
                    if (expectedWorkspace.equals(manifest.workspace())) {
                        summaries.add(SessionSummary.from(manifest));
                    }
                } catch (IOException e) {
                    log.warn("Ignoring unreadable session manifest: {}", manifestFile, e);
                }
            }
        }
        return summaries.stream()
                .sorted(Comparator.comparingLong(SessionSummary::updatedAt).reversed())
                .limit(limit)
                .toList();
    }

    private SessionHandle openHandle(Path directory, SessionManifest manifest,
                                     List<SessionEvent> knownEvents,
                                     String incompleteTailWarning) throws IOException {
        Path lockFile = directory.resolve("session.lock");
        createSecureFile(lockFile);
        FileChannel lockChannel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            closeQuietly(lockChannel);
            throw new SessionLockedException("session is already open for writing: " + manifest.sessionId(), e);
        }
        if (lock == null) {
            closeQuietly(lockChannel);
            throw new SessionLockedException("session is already open for writing: " + manifest.sessionId());
        }

        try {
            Path eventsFile = directory.resolve("events.jsonl");
            ReadEventsResult readResult = knownEvents == null
                    ? readEvents(eventsFile)
                    : new ReadEventsResult(knownEvents, false, -1L);
            if (readResult.incompleteFinalLine()) {
                isolateIncompleteTail(eventsFile, readResult.committedLength());
            }
            SessionProjection projection = replayer.replay(manifest, readResult.events());
            if (readResult.incompleteFinalLine() && incompleteTailWarning != null) {
                projection = projection.withWarning(incompleteTailWarning);
            }
            long actualLastSequence = projection.lastAppliedSequence();
            SessionManifest repaired = withState(manifest, manifest.closed(), actualLastSequence,
                    Math.max(manifest.updatedAt(), lastModified(directory.resolve("events.jsonl"))));
            if (!repaired.equals(manifest)) {
                writeManifest(directory, repaired);
            }
            return new SessionHandle(directory, repaired, readResult.events(), projection,
                    lockChannel, lock);
        } catch (Exception e) {
            try {
                lock.release();
            } finally {
                closeQuietly(lockChannel);
            }
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }

    private ReadEventsResult readEvents(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String content = new String(bytes, StandardCharsets.UTF_8);
        boolean endsWithNewline = content.endsWith("\n");
        String[] lines = content.split("\n", -1);
        int count = endsWithNewline ? lines.length - 1 : lines.length;
        List<SessionEvent> events = new ArrayList<>();
        boolean incompleteFinalLine = false;
        long committedLength = bytes.length;
        for (int index = 0; index < count; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            try {
                events.add(JSON.readValue(line, SessionEvent.class));
            } catch (IOException e) {
                boolean finalPhysicalLine = index == count - 1 && !endsWithNewline;
                if (finalPhysicalLine) {
                    incompleteFinalLine = true;
                    committedLength = lastNewlineOffset(bytes) + 1L;
                    break;
                }
                throw new CorruptSessionLogException(
                        "invalid JSON in session event log at line " + (index + 1), e);
            }
        }
        return new ReadEventsResult(events, incompleteFinalLine, committedLength);
    }

    private static void isolateIncompleteTail(Path eventsFile, long committedLength) throws IOException {
        byte[] bytes = Files.readAllBytes(eventsFile);
        if (committedLength < 0 || committedLength >= bytes.length) {
            return;
        }
        Path fragment = eventsFile.resolveSibling(
                "events.incomplete-" + System.currentTimeMillis() + ".part");
        try (FileChannel fragmentChannel = FileChannel.open(fragment,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer tail = ByteBuffer.wrap(bytes, (int) committedLength,
                    bytes.length - (int) committedLength);
            while (tail.hasRemaining()) {
                fragmentChannel.write(tail);
            }
            fragmentChannel.force(true);
        }
        setPosixPermissionsIfSupported(fragment, FILE_PERMISSIONS);
        try (FileChannel eventChannel = FileChannel.open(eventsFile, StandardOpenOption.WRITE)) {
            eventChannel.truncate(committedLength);
            eventChannel.force(false);
        }
    }

    private static int lastNewlineOffset(byte[] bytes) {
        for (int index = bytes.length - 1; index >= 0; index--) {
            if (bytes[index] == '\n') {
                return index;
            }
        }
        return -1;
    }

    private SessionManifest readManifest(Path directory) throws IOException {
        Path file = directory.resolve("manifest.json");
        if (Files.notExists(file)) {
            throw new IOException("session does not exist: " + directory.getFileName());
        }
        SessionManifest manifest = JSON.readValue(file.toFile(), SessionManifest.class);
        if (!directory.getFileName().toString().equals(manifest.sessionId())) {
            throw new CorruptSessionLogException("manifest session id does not match directory");
        }
        return manifest;
    }

    private static void writeManifest(Path directory, SessionManifest manifest) throws IOException {
        Path target = directory.resolve("manifest.json");
        Path temporary = directory.resolve("manifest.json.tmp");
        byte[] bytes = JSON.writeValueAsBytes(manifest);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        setPosixPermissionsIfSupported(temporary, FILE_PERMISSIONS);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        setPosixPermissionsIfSupported(target, FILE_PERMISSIONS);
    }

    private static SessionManifest withState(SessionManifest manifest, boolean closed,
                                             long sequence, long updatedAt) {
        return new SessionManifest(manifest.schemaVersion(), manifest.sessionId(),
                manifest.workspace(), manifest.provider(), manifest.model(), manifest.createdAt(),
                updatedAt, manifest.parentSessionId(), closed, sequence,
                manifest.resumeUnsafe(), manifest.legacySourceSha256());
    }

    private static Path normalizeWorkspace(Path workspace) throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        return workspace.toRealPath().normalize();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireSafeSessionId(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "sessionId must contain only letters, numbers, '.', '_' or '-'");
        }
        return sessionId;
    }

    private static String generateSessionId() {
        return "session-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
    }

    private static long lastModified(Path file) throws IOException {
        return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
    }

    private static void createSecureDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        setPosixPermissionsIfSupported(directory, DIRECTORY_PERMISSIONS);
    }

    private static void createSecureFile(Path file) throws IOException {
        if (Files.notExists(file)) {
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
            } catch (UnsupportedOperationException e) {
                Files.createFile(file);
            }
        }
        setPosixPermissionsIfSupported(file, FILE_PERMISSIONS);
    }

    private static void setPosixPermissionsIfSupported(Path path,
                                                        Set<PosixFilePermission> permissions)
            throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX file systems use platform ACLs.
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Best effort after a failed open.
        }
    }

    @Override
    public void close() {
        // Handles own their locks; the store itself has no open resources.
    }

    public record SessionCreateRequest(Path workspace, String provider, String model,
                                       String parentSessionId, String mode, String actor) {
    }

    private record ReadEventsResult(List<SessionEvent> events, boolean incompleteFinalLine,
                                    long committedLength) {
    }

    public final class SessionHandle implements AutoCloseable {
        private final Path directory;
        private final FileChannel lockChannel;
        private final FileLock lock;
        private final List<SessionEvent> events;
        private SessionManifest manifest;
        private SessionProjection projection;
        private SessionResumeResult resumeResult;
        private boolean released;

        private SessionHandle(Path directory, SessionManifest manifest, List<SessionEvent> events,
                              SessionProjection projection, FileChannel lockChannel, FileLock lock) {
            this.directory = directory;
            this.manifest = manifest;
            this.events = new ArrayList<>(events);
            this.projection = projection;
            this.lockChannel = lockChannel;
            this.lock = lock;
            this.resumeResult = new SessionResumeResult(
                    manifest.sessionId(), false, 0, 0, projection.warnings());
        }

        public String sessionId() {
            return manifest.sessionId();
        }

        public Path sessionDirectory() {
            return directory;
        }

        public SessionProjection projection() {
            return projection;
        }

        public SessionResumeResult resumeResult() {
            return resumeResult;
        }

        private void recoverInterruptedState() throws IOException {
            List<String> requestIds = List.copyOf(projection.incompleteRequestIds());
            List<SessionProjection.PendingToolInvocation> pendingTools =
                    List.copyOf(projection.pendingTools().values());
            boolean interrupted = !requestIds.isEmpty() || !pendingTools.isEmpty();
            if (!interrupted) {
                return;
            }
            ObjectNode interrupt = JSON.createObjectNode()
                    .put("incompleteRequests", requestIds.size())
                    .put("pendingTools", pendingTools.size());
            append(new SessionEventDraft(SessionEvent.Types.SESSION_INTERRUPT,
                    "react", "session", "resume", false,
                    SessionEvent.SurfaceOperation.none(), interrupt));
            for (String requestId : requestIds) {
                ObjectNode failed = JSON.createObjectNode()
                        .put("requestId", requestId)
                        .put("reason", "interrupted before recovery");
                append(new SessionEventDraft(SessionEvent.Types.REQUEST_FAILED,
                        "react", "session", "resume", false,
                        SessionEvent.SurfaceOperation.none(), failed));
            }
            for (SessionProjection.PendingToolInvocation pending : pendingTools) {
                String content = "Tool call was interrupted before completion and was not retried: "
                        + pending.name();
                ObjectNode result = JSON.createObjectNode()
                        .put("invocationId", pending.invocationId())
                        .put("status", "interrupted");
                result.set("message", JSON.valueToTree(
                        com.codeagent.llm.LlmClient.Message.tool(pending.invocationId(), content)));
                append(new SessionEventDraft(SessionEvent.Types.TOOL_RESULT,
                        "react", "session", "resume", false,
                        SessionEvent.SurfaceOperation.append(), result));
            }
            resumeResult = new SessionResumeResult(manifest.sessionId(), true,
                    requestIds.size(), pendingTools.size(), projection.warnings());
        }

        public synchronized SessionEvent append(SessionEventDraft draft) throws IOException {
            Objects.requireNonNull(draft, "draft");
            if (released) {
                throw new IllegalStateException("session handle is closed");
            }
            long sequence = projection.lastAppliedSequence() + 1;
            SessionEvent event = new SessionEvent(SessionEvent.CURRENT_SCHEMA_VERSION,
                    manifest.sessionId(), sequence, System.currentTimeMillis(), draft.type(),
                    draft.mode(), draft.actor(), draft.source(), draft.ignorable(),
                    draft.surface(), draft.payload());
            byte[] json = JSON.writeValueAsBytes(event);
            ByteBuffer buffer = ByteBuffer.allocate(json.length + 1);
            buffer.put(json).put((byte) '\n').flip();
            try (FileChannel eventChannel = FileChannel.open(directory.resolve("events.jsonl"),
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                while (buffer.hasRemaining()) {
                    eventChannel.write(buffer);
                }
                eventChannel.force(false);
            }

            events.add(event);
            projection = replayer.replay(manifest, events);
            manifest = withState(manifest,
                    SessionEvent.Types.SESSION_END.equals(event.type()), event.sequence(), event.timestamp());
            try {
                writeManifest(directory, manifest);
            } catch (IOException e) {
                log.warn("Session event committed but manifest update failed: session={}, sequence={}",
                        manifest.sessionId(), event.sequence(), e);
            }
            return event;
        }

        public void markClosed(String reason) throws IOException {
            ObjectNode payload = JSON.createObjectNode();
            if (reason != null && !reason.isBlank()) {
                payload.put("reason", reason.trim());
            }
            append(new SessionEventDraft(SessionEvent.Types.SESSION_END,
                    "system", "session", "session-store", false,
                    SessionEvent.SurfaceOperation.none(), payload));
        }

        @Override
        public synchronized void close() throws IOException {
            if (released) {
                return;
            }
            released = true;
            try {
                lock.release();
            } finally {
                lockChannel.close();
            }
        }
    }

    public static class SessionLockedException extends IOException {
        public SessionLockedException(String message) {
            super(message);
        }

        public SessionLockedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class WorkspaceMismatchException extends IOException {
        public WorkspaceMismatchException(String message) {
            super(message);
        }
    }

    public static class CorruptSessionLogException extends IOException {
        public CorruptSessionLogException(String message) {
            super(message);
        }

        public CorruptSessionLogException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
