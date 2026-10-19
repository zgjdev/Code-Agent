package com.codeagent.cli;

import com.codeagent.agent.Agent;
import com.codeagent.history.*;
import com.codeagent.runtime.execution.SessionExecutionContext;
import com.codeagent.runtime.execution.SessionExecutionContextRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Session selection only: no execution submission, model call, or release of saved work. */
final class SessionResumeController {
    @FunctionalInterface interface BusyLookup { boolean busy(String id) throws Exception; }
    @FunctionalInterface interface SessionBinding { void bind(String id) throws Exception; }
    private final SessionStore store;
    private final Path workspace;
    private final SessionResumeService directory;
    private final SessionTranscriptReader reader;
    private final SessionExecutionContextRegistry<SessionExecutionContext> contexts;
    private final ReentrantLock mutationGate;
    private final AtomicReference<SessionStore.SessionHandle> active;
    private final AtomicReference<Agent> agent;
    private final BusyLookup busy, interaction;
    private final SessionBinding binding;
    private final Supplier<SessionStore.SessionCreateRequest> request;

    SessionResumeController(SessionStore store, Path workspace, SessionResumeService directory,
            SessionExecutionContextRegistry<SessionExecutionContext> contexts, ReentrantLock mutationGate,
            AtomicReference<SessionStore.SessionHandle> active, AtomicReference<Agent> agent,
            BusyLookup busy, BusyLookup interaction, SessionBinding binding,
            Supplier<SessionStore.SessionCreateRequest> request) {
        this.store = Objects.requireNonNull(store);
        this.workspace = Objects.requireNonNull(workspace);
        this.directory = Objects.requireNonNull(directory);
        this.reader = new SessionTranscriptReader(store);
        this.contexts = Objects.requireNonNull(contexts);
        this.mutationGate = Objects.requireNonNull(mutationGate);
        this.active = Objects.requireNonNull(active);
        this.agent = Objects.requireNonNull(agent);
        this.busy = Objects.requireNonNull(busy);
        this.interaction = Objects.requireNonNull(interaction);
        this.binding = Objects.requireNonNull(binding);
        this.request = Objects.requireNonNull(request);
    }

    List<SessionListEntry> list(int offset, String query) throws IOException {
        return directory.list(workspace, offset, SessionResumeService.DEFAULT_PAGE_SIZE, query);
    }
    String startupSummary() throws IOException {
        int parents = 0, waiting = 0;
        List<SessionListEntry> page;
        do {
            page = directory.list(workspace, parents, 100, "");
            parents += page.size();
            waiting += page.stream().mapToInt(SessionListEntry::waitingExecutionCount).sum();
        } while (page.size() == 100);
        return "当前项目 " + parents + " 个历史会话，" + waiting + " 条输入等待用户继续。";
    }
    String latest() throws IOException {
        return directory.latestSafeNonemptyParent(workspace)
                .orElseThrow(() -> new IOException("当前项目没有可恢复会话")).summary().sessionId();
    }
    SessionListEntry attach(String id) throws Exception {
        lock();
        try {
            requireIdle(id);
            SessionListEntry entry = directory.requireAttachable(id, workspace);
            select(id);
            return entry;
        } finally { mutationGate.unlock(); }
    }
    SessionStore.SessionHandle ensureSession() throws Exception {
        if (active.get() != null) return active.get();
        lock();
        try { return active.get() == null ? createLocked() : active.get(); }
        finally { mutationGate.unlock(); }
    }
    SessionStore.SessionHandle newSession() throws Exception {
        lock();
        try { requireIdle(null); return createLocked(); }
        finally { mutationGate.unlock(); }
    }
    TranscriptPage history(String id, TranscriptCursor cursor, long watermark, boolean includeCleared) throws IOException {
        return reader.readPage(id, workspace, cursor, SessionTranscriptReader.DEFAULT_PAGE_SIZE, watermark, includeCleared);
    }
    private SessionStore.SessionHandle createLocked() throws Exception {
        String id;
        try (var created = store.create(request.get())) { id = created.sessionId(); }
        select(id);
        return active.get();
    }
    private void lock() {
        if (!mutationGate.tryLock()) throw new IllegalStateException("共享运行态正在执行，请稍后重试");
    }
    private void requireIdle(String target) throws Exception {
        String source = active.get() == null ? null : active.get().sessionId();
        if ((source != null && (busy.busy(source) || interaction.busy(source)))
                || (target != null && (busy.busy(target) || interaction.busy(target))))
            throw new IllegalStateException("源或目标会话正在执行或等待审批");
    }
    private void select(String id) throws Exception {
        var previous = active.get();
        Agent previousAgent = agent.get();
        try {
            // Host binding also uses this reentrant mutation gate; never nest registry leases.
            binding.bind(id);
            try (var lease = contexts.acquire(id)) {
                var selected = lease.context();
                selected.activateSharedBindings();
                agent.set(selected.agent());
                active.set(selected.session());
            }
        } catch (Exception failure) {
            active.set(previous);
            agent.set(previousAgent);
            try {
                if (previous != null) {
                    try (var lease = contexts.acquire(previous.sessionId())) { lease.context().activateSharedBindings(); }
                } else { previousAgent.activateSharedToolContext(); }
            } catch (Exception rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        if (previous != null && !previous.sessionId().equals(id)) {
            try { contexts.evictIfIdle(previous.sessionId(), id); }
            catch (Exception ignored) { /* An eviction failure must not roll back a successful selection. */ }
        }
    }
}
