package com.codeagent.rag;

import com.codeagent.rag.embedding.*;
import com.codeagent.rag.stage.*;
import com.codeagent.search.CodeSearchService;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Owns provider leases and a single JDBC index; expensive inference never holds the DB monitor. */
public final class DefaultCodeRetrievalService implements CodeRetrievalService {
    private final SqliteRetrievalIndex index;
    private final Object lifecycle = new Object();
    private Slot slot;
    private long providerEpoch;
    private int operations;
    private volatile boolean closed;
    private boolean indexClosed;
    private volatile Path lastProjectRoot;
    private volatile WorkspaceCodeIndexManager maintenance;
    private Path remoteRoot;
    private BooleanSupplier remoteConsent = () -> false;

    public DefaultCodeRetrievalService(SqliteRetrievalIndex index, EmbeddingResolution resolution) {
        this.index = Objects.requireNonNull(index);
        this.slot = new Slot(normalize(resolution));
    }
    @Deprecated(forRemoval = false)
    public DefaultCodeRetrievalService(SqliteRetrievalIndex index, CodeSearchService ignored, EmbeddingResolution resolution) {
        this(index, resolution);
    }
    public void setMaintenance(WorkspaceCodeIndexManager manager) { maintenance = manager; }
    public AutoIndexStatus maintenanceStatus(Path root) {
        var manager = maintenance;
        return manager == null ? AutoIndexStatus.manual() : manager.status(root);
    }
    public void reconfigureEmbeddingForProject(EmbeddingResolution resolution, Path root, BooleanSupplier consent) {
        synchronized (lifecycle) {
            remoteRoot = canonical(root);
            remoteConsent = consent == null ? () -> false : consent;
            replaceProvider(resolution);
        }
        var manager = maintenance;
        if (manager != null) manager.providerChanged();
    }
    @Override public void reconfigureEmbedding(EmbeddingResolution resolution) {
        synchronized (lifecycle) { replaceProvider(resolution); }
        var manager = maintenance;
        if (manager != null) manager.providerChanged();
    }
    private void replaceProvider(EmbeddingResolution resolution) {
        ensureOpen();
        EmbeddingResolution next = normalize(resolution);
        Slot previous = slot;
        if (previous.resolution.provider().orElse(null) == next.provider().orElse(null)) {
            previous.resolution = next;
        } else {
            slot = new Slot(next);
            previous.retired = true;
            releaseSlot(previous);
        }
        providerEpoch++;
    }
    @Override public RetrievalResponse search(RetrievalRequest request) {
        lastProjectRoot = request.projectRoot();
        try (Lease lease = acquire(request.projectRoot(), true)) {
            boolean empty;
            try { empty = index.status(request.projectRoot()).chunkCount() == 0; }
            catch (Exception e) { throw new IllegalStateException("Unable to inspect retrieval index", e); }
            Optional<EmbeddingProvider> prepared = lease.provider;
            long inferenceMillis = 0;
            if (!empty && prepared.isPresent()) {
                long started = System.nanoTime();
                prepared = Optional.of(prepareQuery(prepared.orElseThrow(), request.query()));
                inferenceMillis = (System.nanoTime() - started) / 1_000_000;
            }
            var context = new RetrievalContext(request, index, prepared);
            RetrievalStageRunner.Result result;
            RetrievalPipeline.Result budgeted;
            Optional<RepositoryMap> map;
            synchronized (index) {
                result = empty ? new RetrievalStageRunner.Result(Map.of(), Map.of(),
                        Map.of(RetrievalSource.FTS_TERMS, 0, new SemanticRetriever().source(context), 0), List.of("index_empty"))
                        : new RetrievalStageRunner().run(List.of(new TermFtsRetriever(), new SemanticRetriever()), context);
                budgeted = new RetrievalPipeline().apply(result.rankings(), request);
                map = request.intent() == RetrievalIntent.ARCHITECTURE
                        ? Optional.of(new RepositoryMapSelector(index).select(request.projectRoot(), request.query(), 1500))
                        : Optional.empty();
            }
            var reasons = new ArrayList<>(result.degradedReasonCodes());
            var freshness = new RetrievalFreshnessChecker().check(request.projectRoot(), index, budgeted.hits());
            freshness.values().stream().filter(s -> !"verified".equals(s)).map(s -> "index_file_" + s)
                    .distinct().sorted().forEach(reasons::add);
            if (lease.provider.isEmpty() && lease.reason != null && !lease.reason.isBlank()
                    && !reasons.contains(lease.reason)) reasons.add(lease.reason);
            var durations = new EnumMap<RetrievalSource, Long>(RetrievalSource.class);
            durations.putAll(result.durations());
            if (!empty && prepared.isPresent()) durations.merge(new SemanticRetriever().source(context), inferenceMillis, Long::sum);
            var status = maintenanceStatus(request.projectRoot());
            boolean pending = !Set.of("idle", "manual", "paused").contains(status.state());
            var diagnostics = new RetrievalDiagnostics(lease.provider.map(EmbeddingProvider::id).orElse("off"),
                    durations, result.hits(), reasons, SqliteRetrievalIndex.SCHEMA_VERSION, freshness, status);
            return new RetrievalResponse(budgeted.hits(), map, diagnostics,
                    budgeted.partial() || !reasons.isEmpty() || pending || map.map(RepositoryMap::partial).orElse(false));
        }
    }
    private EmbeddingProvider prepareQuery(EmbeddingProvider provider, String query) {
        List<float[]> vector = null;
        EmbeddingException failure = null;
        try { vector = provider.embedAll(List.of(new EmbeddingInputPolicy().prepareQuery(query))); }
        catch (EmbeddingException e) { failure = e; }
        catch (RuntimeException e) { failure = new EmbeddingException("embedding_query_failed", "Embedding query failed", e); }
        final List<float[]> value = vector;
        final EmbeddingException error = failure;
        return new DelegatingProvider(provider) {
            @Override public List<float[]> embedAll(List<String> ignored) throws EmbeddingException {
                if (error != null) throw error;
                return value;
            }
        };
    }
    @Override public IndexRefreshResult refresh(IndexRefreshRequest request) {
        var manager = maintenance;
        return manager == null ? directRefresh(request) : manager.refresh(request);
    }
    public IndexRefreshResult directRefresh(IndexRefreshRequest request) {
        lastProjectRoot = request.projectRoot();
        var lexical = reconcileLexical(request);
        var reasons = new LinkedHashSet<>(lexical.reasonCodes());
        for (var work : missingEmbeddingWork(request.projectRoot(), Integer.MAX_VALUE)) {
            try { backfill(work, () -> true); }
            catch (Exception e) { reasons.add("embedding_failed"); }
        }
        return new IndexRefreshResult(lexical.changedFiles(), lexical.unchangedFiles(), lexical.deletedFiles(),
                lexical.failedFiles(), List.copyOf(reasons));
    }
    public IndexRefreshResult reconcileLexical(IndexRefreshRequest request) {
        lastProjectRoot = request.projectRoot();
        try (Lease ignored = acquire(request.projectRoot(), false)) {
            return coordinator(Optional.empty()).reconcileLexical(request);
        }
    }
    public IndexRefreshResult refreshLexicalPaths(Path root, List<Path> paths) {
        lastProjectRoot = root;
        try (Lease ignored = acquire(root, false)) {
            return coordinator(Optional.empty()).refreshPaths(root, paths);
        }
    }
    public List<EmbeddingWorkItem> missingEmbeddingWork(Path root, int limit) {
        try (Lease lease = acquire(root, false)) {
            if (lease.provider.isEmpty()) return List.of();
            return coordinator(lease.provider).missingEmbeddingWork(root, limit);
        }
    }
    public boolean backfill(EmbeddingWorkItem work, BooleanSupplier stillCurrent) throws Exception {
        try (Lease lease = acquire(work.projectRoot(), false)) {
            if (lease.provider.isEmpty() || !stillCurrent.getAsBoolean()) return false;
            var coordinator = coordinator(lease.provider);
            var batch = coordinator.computeEmbeddings(work);
            synchronized (lifecycle) {
                if (closed || lease.epoch != providerEpoch || !allowed(lease.owner, work.projectRoot())
                        || !stillCurrent.getAsBoolean()) return false;
                return coordinator.commitEmbeddings(batch);
            }
        }
    }
    public void clear(Path root) {
        var manager = maintenance;
        if (manager != null) { manager.clear(root); return; }
        directClear(root);
    }
    public void directClear(Path root) {
        lastProjectRoot = root;
        try (Lease ignored = acquire(root, false)) { coordinator(Optional.empty()).clear(root); }
    }
    private IndexCoordinator coordinator(Optional<EmbeddingProvider> provider) {
        return new IndexCoordinator(index, provider).withCommitGuard(lifecycle, () -> !closed);
    }
    @Override public RetrievalIndexStatus status() {
        Path root = lastProjectRoot;
        if (closed || root == null) return new RetrievalIndexStatus(!closed, false, 0, 0);
        try (Lease ignored = acquire(root, false)) { return index.status(root); }
        catch (Exception e) { return new RetrievalIndexStatus(false, false, 0, 0); }
    }
    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            providerEpoch++;
            slot.retired = true;
            releaseSlot(slot);
            closeIndexIfIdle();
        }
    }
    private Lease acquire(Path root, boolean query) {
        synchronized (lifecycle) {
            ensureOpen();
            Slot current = slot;
            current.refs++;
            operations++;
            Optional<EmbeddingProvider> provider = allowed(current, root)
                    ? current.resolution.provider().map(p -> new DelegatingProvider(p) {
                        @Override public List<float[]> embedAll(List<String> inputs) throws EmbeddingException {
                            List<float[]> result = new ArrayList<>();
                            int size = query ? Math.max(1, inputs.size()) : 8;
                            for (int offset = 0; offset < inputs.size(); offset += size) {
                                current.enter(query);
                                try {
                                    synchronized (lifecycle) {
                                        if (closed || current != slot || !allowed(current, root))
                                            throw new EmbeddingException("embedding_cancelled", "Embedding capability expired");
                                    }
                                    result.addAll(p.embedAll(inputs.subList(offset, Math.min(inputs.size(), offset + size))));
                                } finally { current.exit(); }
                            }
                            return List.copyOf(result);
                        }
                    }) : Optional.empty();
            return new Lease(current, provider, providerEpoch,
                    provider.isEmpty() && current.resolution.provider().isPresent()
                            ? "remote_embedding_consent_required" : current.resolution.reason());
        }
    }
    private boolean allowed(Slot current, Path root) {
        return current.resolution.provider().map(p -> p.locality() != EmbeddingLocality.REMOTE
                || (remoteRoot != null && remoteRoot.equals(canonical(root)) && safeConsent())).orElse(true);
    }
    private boolean safeConsent() { try { return remoteConsent.getAsBoolean(); } catch (Exception e) { return false; } }
    private void releaseSlot(Slot current) {
        if (current.retired && current.refs == 0 && !current.disposed) {
            current.disposed = true;
            current.resolution.provider().ifPresent(p -> { try { p.close(); } catch (Exception ignored) {} });
        }
    }
    private void closeIndexIfIdle() {
        if (closed && operations == 0 && !indexClosed) {
            indexClosed = true;
            try { index.close(); } catch (Exception ignored) {}
        }
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Retrieval service is closed"); }
    private static Path canonical(Path root) {
        try { return root.toRealPath().normalize(); }
        catch (Exception e) { return root.toAbsolutePath().normalize(); }
    }
    private static EmbeddingResolution normalize(EmbeddingResolution value) {
        return value == null ? new EmbeddingResolution(Optional.empty(), "embedding_disabled", false) : value;
    }
    private final class Lease implements AutoCloseable {
        final Slot owner;
        final Optional<EmbeddingProvider> provider;
        final long epoch;
        final String reason;
        Lease(Slot owner, Optional<EmbeddingProvider> provider, long epoch, String reason) {
            this.owner = owner; this.provider = provider; this.epoch = epoch; this.reason = reason;
        }
        public void close() {
            synchronized (lifecycle) { owner.refs--; operations--; releaseSlot(owner); closeIndexIfIdle(); }
        }
    }
    private static class DelegatingProvider implements EmbeddingProvider {
        final EmbeddingProvider delegate;
        DelegatingProvider(EmbeddingProvider delegate) { this.delegate = delegate; }
        public String id() { return delegate.id(); }
        public String modelId() { return delegate.modelId(); }
        public EmbeddingSpaceDescriptor space() { return delegate.space(); }
        public EmbeddingLocality locality() { return delegate.locality(); }
        public List<float[]> embedAll(List<String> inputs) throws EmbeddingException { return delegate.embedAll(inputs); }
    }
    private static final class Slot {
        EmbeddingResolution resolution;
        int refs;
        boolean retired, disposed;
        boolean busy;
        int queryWaiters;
        Slot(EmbeddingResolution resolution) { this.resolution = resolution; }
        synchronized void enter(boolean query) throws EmbeddingException {
            if (query) queryWaiters++;
            try {
                while (busy || (!query && queryWaiters > 0)) wait();
                busy = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EmbeddingException("embedding_interrupted", "Embedding work interrupted", e);
            } finally {
                if (query) {
                    queryWaiters--;
                    notifyAll();
                }
            }
        }
        synchronized void exit() { busy = false; notifyAll(); }
    }
}
