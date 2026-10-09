package com.codeagent.rag;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.rag.embedding.EmbeddingException;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicitly attached CLI resource. No session, renderer, model download or authorization interaction. */
public final class WorkspaceCodeIndexManager implements AutoCloseable {
    private static final int MAX_DIRTY = 4096, MAX_EMBEDDING = 256;
    private final DefaultCodeRetrievalService service;
    private final CodeAgentConfig.AutoIndexConfig config;
    private final Clock clock;
    private final Object state = new Object(), lexicalGate = new Object();
    private final Map<Path, Project> projects = new LinkedHashMap<>();
    private final Map<WatchKey, WatchedDirectory> directories = new HashMap<>();
    private final Set<Path> watchedPaths = new HashSet<>();
    private final Deque<VectorTask> vectors = new ArrayDeque<>();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService embedding;
    private final WatchService watcher;
    private final boolean watcherUnavailable;
    private Thread watcherThread;
    private boolean vectorRunning;
    private volatile boolean closed;
    private int cursor;
    private int vectorCursor;

    public WorkspaceCodeIndexManager(DefaultCodeRetrievalService service, CodeAgentConfig.AutoIndexConfig config) throws IOException {
        this(service, config, Clock.systemUTC(), true);
    }
    public WorkspaceCodeIndexManager(DefaultCodeRetrievalService service, CodeAgentConfig.AutoIndexConfig config,
                                    Clock clock, boolean watchEnabled) throws IOException {
        config.validate();
        this.service = Objects.requireNonNull(service);
        this.config = config;
        this.clock = clock;
        WatchService candidate = null;
        boolean unavailable = false;
        if (watchEnabled && config.isEnabled()) {
            try { candidate = FileSystems.getDefault().newWatchService(); }
            catch (IOException | UnsupportedOperationException e) { unavailable = true; }
        }
        watcher = candidate;
        watcherUnavailable = unavailable;
        scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("codeagent-index-lexical"));
        embedding = Executors.newSingleThreadExecutor(daemonFactory("codeagent-index-vector"));
        service.setMaintenance(this);
        if (watcher != null) {
            watcherThread = daemonFactory("codeagent-index-watch").newThread(this::watchLoop);
            watcherThread.start();
        }
        if (config.isEnabled()) scheduler.scheduleWithFixedDelay(this::tickSafely, 0, 100, TimeUnit.MILLISECONDS);
    }
    private static ThreadFactory daemonFactory(String name) {
        return runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
    }
    public void register(Path requestedRoot) throws IOException {
        if (!config.isEnabled()) return;
        Path root = new IndexPathPolicy(requestedRoot).root();
        synchronized (state) {
            if (closed) throw new IllegalStateException("Index manager closed");
            if (projects.containsKey(root)) return;
            Project project = new Project(root);
            project.watcherFailed = watcherUnavailable;
            projects.put(root, project);
            state.notifyAll();
        }
        if (watcher != null) try { registerTree(root, root); }
        catch (IOException e) { watcherFailed(root); }
    }
    public void unregister(Path root) {
        Path key = canonical(root);
        synchronized (state) {
            Project project = projects.remove(key);
            if (project == null) return;
            project.epoch++;
            vectors.removeIf(task -> task.project == project);
            directories.entrySet().removeIf(entry -> {
                if (!entry.getValue().root.equals(key)) return false;
                entry.getKey().cancel(); watchedPaths.remove(entry.getValue().directory); return true;
            });
            state.notifyAll();
        }
    }
    public void pathChanged(Path root, Path path) {
        Path key = canonical(root);
        Path absolute = (path.isAbsolute() ? path : key.resolve(path)).toAbsolutePath().normalize();
        if (!absolute.startsWith(key)) return;
        synchronized (state) {
            Project project = projects.get(key);
            if (project == null || project.paused || closed) return;
            if (absolute.equals(key.resolve(".gitignore"))) { invalidate(project); return; }
            long now = clock.millis();
            Pending previous = project.dirty.get(absolute);
            project.dirty.put(absolute, new Pending(previous == null ? now : previous.first, now));
            project.generations.merge(absolute, 1L, Long::sum);
            project.embeddingReady.remove(key.relativize(absolute));
            if (project.dirty.size() > MAX_DIRTY) invalidate(project);
            state.notifyAll();
        }
    }
    public void workspaceChanged(Path root) {
        synchronized (state) {
            Project project = projects.get(canonical(root));
            if (project != null && !project.paused && !closed) invalidate(project);
        }
    }
    private void invalidate(Project project) {
        project.epoch++;
        project.full = true;
        project.dirty.clear(); project.generations.clear();
        project.embeddingReady.clear();
        vectors.removeIf(task -> task.project == project);
        state.notifyAll();
    }
    public void providerChanged() {
        synchronized (state) {
            for (Project project : projects.values()) {
                project.embeddingBlocked = false;
                project.error = lexicalError(project); project.retries = 0;
                if (!project.paused) invalidate(project);
            }
        }
    }
    private void tickSafely() {
        try { tick(); }
        catch (Exception e) {
            synchronized (state) {
                for (Project project : projects.values()) if (project.lexicalRunning) {
                    project.lexicalRunning = false; project.error = "maintenance_failed";
                    project.full = true; project.nextAttempt = clock.millis() + 5000;
                }
                state.notifyAll();
            }
        }
    }
    private void tick() throws Exception {
        Project selected = null;
        List<Path> paths = List.of(); boolean full = false;
        synchronized (state) {
            if (closed) return;
            long now = clock.millis();
            var candidates = new ArrayList<>(projects.values());
            for (int n = 0; n < candidates.size(); n++) {
                Project project = candidates.get((cursor + n) % candidates.size());
                if (project.paused || project.lexicalRunning || now < project.nextAttempt) continue;
                if (now - project.lastScan >= config.getReconcileIntervalSeconds() * 1000) project.full = true;
                var ready = project.dirty.entrySet().stream().filter(entry ->
                        now - entry.getValue().last >= config.getDebounceMillis()
                        || now - entry.getValue().first >= config.getMaxDebounceMillis()).map(Map.Entry::getKey).toList();
                if (project.full || !ready.isEmpty()) {
                    selected = project; full = project.full; project.full = false;
                    paths = full ? List.copyOf(project.dirty.keySet()) : ready;
                    paths.forEach(project.dirty::remove);
                    project.lexicalRunning = true;
                    cursor = (cursor + n + 1) % candidates.size();
                    break;
                }
            }
        }
        if (selected != null) {
            final Project project = selected;
            IndexRefreshResult result;
            long lexicalEpoch;
            synchronized (lexicalGate) {
                synchronized (state) {
                    if (project.paused || closed || projects.get(project.root) != project) {
                        project.lexicalRunning = false; state.notifyAll(); return;
                    }
                    lexicalEpoch = project.epoch;
                }
                result = full ? service.reconcileLexical(new IndexRefreshRequest(project.root, false))
                        : service.refreshLexicalPaths(project.root, paths);
            }
            if (full && watcher != null) try {
                registerTree(project.root, project.root);
                synchronized (state) { project.watcherFailed = false; }
            }
            catch (IOException e) { watcherFailed(project.root); }
            synchronized (state) {
                project.lexicalRunning = false;
                if (closed || project.paused || projects.get(project.root) != project || lexicalEpoch != project.epoch) {
                    state.notifyAll(); return;
                }
                if (full) project.embeddingReady.clear();
                else for (Path path : paths) project.embeddingReady.remove(project.root.relativize(path));
                project.embeddingReady.addAll(result.embeddingReadyPaths());
                // A notification arriving during the scan has not been confirmed by this result.
                for (Path path : project.dirty.keySet()) project.embeddingReady.remove(project.root.relativize(path));
                project.discoverEmbeddings = true;
                if (result.failedFiles() == 0 && !result.reasonCodes().contains("scan_incomplete")) {
                    project.lexicalFailed = false;
                    if (full) project.lastScan = clock.millis();
                    project.nextAttempt = 0;
                    if (!project.embeddingBlocked) project.error = lexicalError(project);
                } else {
                    project.lexicalFailed = true;
                    project.error = "lexical_refresh_failed";
                    project.full = true; project.nextAttempt = clock.millis() + 5000;
                }
                reclaimGenerations(project);
                state.notifyAll();
            }
        }
        // Gather missing metadata outside the state lock. One bounded global queue, fair per-project fill.
        List<Project> contexts;
        synchronized (state) {
            contexts = new ArrayList<>(projects.values());
            if (!contexts.isEmpty()) {
                Collections.rotate(contexts, -(vectorCursor % contexts.size()));
                vectorCursor = (vectorCursor + 1) % contexts.size();
            }
        }
        for (Project project : contexts) {
            int capacity; long epoch;
            Set<Path> eligiblePaths;
            synchronized (state) {
                if (closed || project.paused || project.lexicalRunning
                        || (project.full && clock.millis() >= project.nextAttempt) || !project.dirty.isEmpty()
                        || project.embeddingBlocked || !project.discoverEmbeddings || clock.millis() < project.nextVectorAttempt) continue;
                capacity = Math.min(16, MAX_EMBEDDING - vectors.size() - (vectorRunning ? 1 : 0));
                if (capacity <= 0) break;
                epoch = project.epoch;
                eligiblePaths = Set.copyOf(project.embeddingReady);
            }
            List<EmbeddingWorkItem> missing;
            try { missing = service.missingEmbeddingWork(project.root, capacity, eligiblePaths); }
            catch (RuntimeException failure) {
                synchronized (state) {
                    if (closed || project.paused || epoch != project.epoch || projects.get(project.root) != project) continue;
                    project.error = "embedding_discovery_failed";
                    project.retries++;
                    project.embeddingBlocked = project.retries >= 3;
                    project.nextVectorAttempt = clock.millis() + Math.min(60000, 1000L << Math.min(6, project.retries));
                    state.notifyAll();
                }
                continue;
            }
            synchronized (state) {
                if (closed || project.paused || epoch != project.epoch || projects.get(project.root) != project) continue;
                project.discoverEmbeddings = false;
                for (var work : missing) {
                    Path absolute = project.root.resolve(work.relativePath());
                    if (!project.embeddingReady.contains(work.relativePath())) continue;
                    boolean duplicate = vectors.stream().anyMatch(task -> task.project == project && task.absolute.equals(absolute));
                    if (!duplicate && !absolute.equals(project.activeVector)) {
                        vectors.add(new VectorTask(project, work, epoch, project.generations.getOrDefault(absolute, 0L), absolute));
                    }
                }
            }
        }
        synchronized (state) {
            if (!closed && !vectorRunning && !vectors.isEmpty()) {
                VectorTask task = vectors.removeFirst(); vectorRunning = true;
                task.project.activeVector = task.absolute;
                embedding.submit(() -> runVector(task));
            }
            state.notifyAll();
        }
    }
    private void runVector(VectorTask task) {
        String error = ""; boolean permanent = false;
        boolean committed = false;
        boolean stale = false;
        try {
            committed = service.backfill(task.work, () -> current(task));
            stale = !committed;
        }
        catch (StaleIndexWorkException | IOException ignored) { stale = true; }
        catch (Exception e) {
            if (current(task)) {
                if (e instanceof EmbeddingException failure) {
                    error = failure.reasonCode();
                    permanent = error.startsWith("local_") || error.contains("consent")
                            || error.contains("authentication") || error.contains("dimension");
                } else error = "embedding_backfill_failed";
            }
        } finally {
            synchronized (state) {
                vectorRunning = false; task.project.activeVector = null;
                task.project.discoverEmbeddings = true;
                boolean ownsCurrentState = current(task);
                if (committed && ownsCurrentState) {
                    task.project.retries = 0;
                    task.project.nextVectorAttempt = 0;
                    task.project.error = lexicalError(task.project);
                }
                if (stale && ownsCurrentState) pathChanged(task.project.root, task.absolute);
                if (!error.isEmpty() && ownsCurrentState) {
                    task.project.error = error;
                    task.project.retries++;
                    task.project.embeddingBlocked = permanent || task.project.retries >= 3;
                    task.project.nextVectorAttempt = clock.millis() + Math.min(60000, 1000L << Math.min(6, task.project.retries));
                    vectors.removeIf(queued -> queued.project == task.project);
                }
                reclaimGenerations(task.project);
                state.notifyAll();
            }
        }
    }
    private static String lexicalError(Project project) {
        return project.lexicalFailed ? "lexical_refresh_failed" : project.watcherFailed ? "watcher_unavailable" : "";
    }
    private boolean current(VectorTask task) {
        synchronized (state) {
            return !closed && !task.project.paused && projects.get(task.project.root) == task.project
                    && task.project.epoch == task.epoch
                    && task.project.embeddingReady.contains(task.work.relativePath())
                    && task.project.generations.getOrDefault(task.absolute, 0L) == task.generation;
        }
    }
    private void reclaimGenerations(Project project) {
        project.generations.keySet().removeIf(path -> !project.dirty.containsKey(path)
                && !path.equals(project.activeVector)
                && vectors.stream().noneMatch(task -> task.project == project && task.absolute.equals(path)));
    }
    public IndexRefreshResult refresh(IndexRefreshRequest request) {
        try { register(request.projectRoot()); }
        catch (IOException e) { throw new IllegalStateException("Unable to register workspace", e); }
        Project project;
        synchronized (lexicalGate) {
            synchronized (state) {
                project = projects.get(canonical(request.projectRoot()));
                if (project != null) { project.paused = true; project.lexicalRunning = true; invalidate(project); }
            }
            try { return service.directRefresh(request); }
            finally {
                synchronized (state) {
                    if (project != null) { project.paused = false; project.lexicalRunning = false; invalidate(project); }
                }
            }
        }
    }
    public AutoIndexStatus status(Path root) {
        synchronized (state) {
            Project project = projects.get(canonical(root));
            if (project == null) return AutoIndexStatus.disabled();
            int pending = (int) vectors.stream().filter(task -> task.project == project).count()
                    + (project.activeVector == null ? 0 : 1);
            String mode = project.paused ? "paused" : project.lexicalRunning || project.full || !project.dirty.isEmpty()
                    ? "updating" : project.embeddingBlocked ? "degraded"
                    : pending > 0 || project.discoverEmbeddings ? "embedding" : "idle";
            return new AutoIndexStatus(mode, project.dirty.size() + (project.full || project.lexicalRunning ? 1 : 0),
                    pending, project.lastScan, project.watcherFailed ? "degraded" : watcher == null ? "off" : "active", project.error);
        }
    }
    /** Operational barrier for explicit callers; timeout never implies the index is fresh. */
    public void awaitIdle(Path root, Duration timeout) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (state) {
            while (true) {
                var current = status(root);
                if (Set.of("idle", "paused", "disabled", "degraded").contains(current.state())) return;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("Index maintenance still pending");
                TimeUnit.NANOSECONDS.timedWait(state, remaining);
            }
        }
    }
    private void registerTree(Path root, Path start) throws IOException {
        IndexPathPolicy policy = new IndexPathPolicy(root);
        if (!policy.isEligibleDirectory(start)) return;
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!policy.isEligibleDirectory(directory)) return FileVisitResult.SKIP_SUBTREE;
                synchronized (state) {
                    if (closed) return FileVisitResult.TERMINATE;
                    if (watchedPaths.add(directory)) {
                        try {
                            WatchKey key = directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                            directories.put(key, new WatchedDirectory(root, directory));
                        } catch (IOException e) { watchedPaths.remove(directory); throw e; }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException { throw error; }
        });
    }
    private void watchLoop() {
        try {
            while (!closed) {
                WatchKey key = watcher.take(); WatchedDirectory directory;
                synchronized (state) { directory = directories.get(key); }
                if (directory != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) { workspaceChanged(directory.root); continue; }
                        if (!(event.context() instanceof Path relative)) continue;
                        Path changed = directory.directory.resolve(relative);
                        try {
                            if (changed.equals(directory.root.resolve(".gitignore"))) { workspaceChanged(directory.root); continue; }
                            IndexPathPolicy policy = new IndexPathPolicy(directory.root);
                            if (Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS) && policy.isEligibleDirectory(changed)) {
                                registerTree(directory.root, changed); workspaceChanged(directory.root);
                            } else if (policy.isEligible(changed) || Files.notExists(changed, LinkOption.NOFOLLOW_LINKS)) {
                                pathChanged(directory.root, changed);
                                if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) workspaceChanged(directory.root);
                            }
                        } catch (IOException e) { watcherFailed(directory.root); }
                    }
                    if (!key.reset()) {
                        synchronized (state) { directories.remove(key); watchedPaths.remove(directory.directory); }
                        workspaceChanged(directory.root);
                    }
                } else key.cancel();
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (ClosedWatchServiceException ignored) { }
    }
    private void watcherFailed(Path root) {
        synchronized (state) {
            Project project = projects.get(canonical(root));
            if (project != null) {
                project.watcherFailed = true;
                project.error = "watcher_unavailable";
                // A failed registration must not reschedule the successful full scan every tick.
                // Startup already has a full scan pending; later recovery uses periodic reconciliation.
                state.notifyAll();
            }
        }
    }
    @Override public void close() {
        shutdown(true);
    }
    /** Roll back an unsuccessful attachment without making the existing retrieval service unusable. */
    public void abortStartup() {
        shutdown(false);
        service.setMaintenance(null);
    }
    private void shutdown(boolean closeService) {
        synchronized (state) {
            if (closed) return; closed = true;
            projects.values().forEach(project -> project.epoch++); vectors.clear(); state.notifyAll();
        }
        if (watcher != null) try { watcher.close(); } catch (IOException ignored) { }
        scheduler.shutdownNow(); embedding.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            scheduler.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            embedding.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (closeService) service.close();
    }
    private static Path canonical(Path root) {
        try { return root.toRealPath(); } catch (IOException e) { return root.toAbsolutePath().normalize(); }
    }
    private static final class Project {
        final Path root;
        final Map<Path, Pending> dirty = new LinkedHashMap<>();
        final Map<Path, Long> generations = new HashMap<>();
        final Set<Path> embeddingReady = new HashSet<>();
        long epoch, lastScan, nextAttempt, nextVectorAttempt;
        int retries;
        boolean full = true, paused, lexicalRunning, lexicalFailed, embeddingBlocked, watcherFailed, discoverEmbeddings;
        Path activeVector;
        String error = "";
        Project(Path root) { this.root = root; }
    }
    private record Pending(long first, long last) { }
    private record WatchedDirectory(Path root, Path directory) { }
    private record VectorTask(Project project, EmbeddingWorkItem work, long epoch, long generation, Path absolute) { }
}
