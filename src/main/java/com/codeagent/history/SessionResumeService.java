package com.codeagent.history;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/** Pure directory queries; attaching a context remains the trusted caller's responsibility. */
public final class SessionResumeService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    private final SessionStore store;
    private final RecoverySummaryLookup lookup;
    @FunctionalInterface public interface RecoverySummaryLookup {
        RecoverySummary lookup(String sessionId, Path workspace) throws IOException;
    }
    public record RecoverySummary(int waitingExecutionCount, boolean hasUnfinishedPlan) {
        public RecoverySummary {
            if (waitingExecutionCount < 0) throw new IllegalArgumentException("negative waiting count");
        }
    }
    public SessionResumeService(SessionStore store) { this(store, (id, workspace) -> new RecoverySummary(0, false)); }
    public SessionResumeService(SessionStore store, RecoverySummaryLookup lookup) {
        this.store = Objects.requireNonNull(store);
        this.lookup = Objects.requireNonNull(lookup);
    }

    public List<SessionListEntry> list(Path workspace, int offset, int limit, String query) throws IOException {
        int pageSize = limit == 0 ? DEFAULT_PAGE_SIZE : limit;
        if (offset < 0 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("invalid directory page");
        String search = TranscriptDisplayText.sanitize(query).strip().toLowerCase(Locale.ROOT);
        return directory(workspace).stream().filter(e -> e.title().toLowerCase(Locale.ROOT).contains(search))
                .skip(offset).limit(pageSize).toList();
    }

    public Optional<SessionListEntry> latestSafeNonemptyParent(Path workspace) throws IOException {
        return directory(workspace).stream().filter(SessionListEntry::attachable).findFirst();
    }

    /** Validation only. Does not open a writer, attach a context or resume any execution. */
    public SessionListEntry requireAttachable(String id, Path workspace) throws IOException {
        Path canonical = workspace.toRealPath();
        SessionManifest manifest = store.readValidatedManifest(id, canonical, null);
        SessionListEntry entry = describe(manifest, canonical);
        if (!entry.attachable()) throw new IOException(entry.unavailableReason());
        return entry;
    }

    private List<SessionListEntry> directory(Path workspace) throws IOException {
        Path canonical = workspace.toRealPath();
        List<SessionListEntry> entries = new ArrayList<>();
        for (String id : store.readSessionIds()) {
            SessionManifest manifest;
            try {
                manifest = store.readValidatedManifest(id, canonical, null);
            } catch (IOException | IllegalArgumentException ignored) {
                // Foreign, child, redirected or unidentified manifests never become directory items.
                continue;
            }
            entries.add(describe(manifest, canonical));
        }
        entries.sort(Comparator.comparingLong((SessionListEntry e) -> e.summary().updatedAt()).reversed()
                .thenComparing(e -> e.summary().sessionId()));
        return List.copyOf(entries);
    }

    private SessionListEntry describe(SessionManifest manifest, Path workspace) throws IOException {
        SessionListEntry.Availability availability = SessionListEntry.Availability.ATTACHABLE;
        String reason = null;
        String title = "未命名会话";
        try {
            SessionReadSnapshot snapshot = store.readParentSnapshot(manifest.sessionId(), workspace, -1);
            Optional<TranscriptEntry> firstUser = SessionTranscriptReader.project(snapshot).stream()
                    .filter(e -> e.role() == TranscriptEntry.Role.USER && !e.text().isBlank()).findFirst();
            if (firstUser.isPresent()) {
                String firstLine = firstUser.get().text().lines().findFirst().orElse("").strip();
                if (!firstLine.isBlank()) {
                    int points = firstLine.codePointCount(0, firstLine.length());
                    title = points <= 80 ? firstLine : firstLine.substring(0, firstLine.offsetByCodePoints(0, 80)) + "…";
                }
            } else {
                availability = SessionListEntry.Availability.EMPTY;
                reason = "会话没有可显示的顶层用户消息";
            }
            if (manifest.resumeUnsafe()) {
                availability = SessionListEntry.Availability.UNSAFE;
                reason = "会话标记为不可安全恢复";
            }
        } catch (IOException e) {
            availability = SessionListEntry.Availability.CORRUPT;
            reason = "会话日志损坏或不可安全读取";
        }
        RecoverySummary recovery = Objects.requireNonNull(lookup.lookup(manifest.sessionId(), workspace));
        if (availability == SessionListEntry.Availability.EMPTY && recovery.waitingExecutionCount() > 0) {
            availability = SessionListEntry.Availability.ATTACHABLE;
            reason = null;
        }
        return new SessionListEntry(SessionSummary.from(manifest), title, recovery.waitingExecutionCount(),
                recovery.hasUnfinishedPlan(), availability, reason);
    }
}
