package com.codeagent.rag;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.rag.embedding.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticIndexConcurrencyTest {
    @Test void oldDiscoveryFailureCannotPolluteNewProviderState(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Files.writeString(root.resolve("Store.java"), "class Store { void saveData() {} }");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var delegate = provider(EmbeddingLocality.IN_PROCESS, () -> {});
        EmbeddingProvider broken = new EmbeddingProvider() {
            public String id() { return delegate.id(); }
            public String modelId() { return delegate.modelId(); }
            public EmbeddingSpaceDescriptor space() {
                entered.countDown(); await(release); throw new IllegalStateException("old metadata failure");
            }
            public EmbeddingLocality locality() { return delegate.locality(); }
            public List<float[]> embedAll(List<String> inputs) { throw new AssertionError("no inference expected"); }
        };
        var config = new CodeAgentConfig.AutoIndexConfig();
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"));
             var service = new DefaultCodeRetrievalService(index, resolution(broken));
             var manager = new WorkspaceCodeIndexManager(service, config, Clock.systemUTC(), false)) {
            manager.register(root);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                service.reconfigureEmbedding(new EmbeddingResolution(Optional.empty(), "off", false));
            } finally { release.countDown(); }
            manager.awaitIdle(root, Duration.ofSeconds(5));
            assertEquals("idle", manager.status(root).state());
            assertTrue(manager.status(root).errorCode().isEmpty());
            assertTrue(terms(index, root, "saveData"));
        } finally { release.countDown(); }
    }
    @Test void successfulBackfillResetsTransientFailureSequence(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Path source = root.resolve("Store.java");
        Files.writeString(source, "class Store { void actionInitial() {} }");
        var failNext = new AtomicBoolean(false);
        var delegate = provider(EmbeddingLocality.IN_PROCESS, () -> {});
        EmbeddingProvider flaky = new EmbeddingProvider() {
            public String id() { return delegate.id(); }
            public String modelId() { return delegate.modelId(); }
            public EmbeddingSpaceDescriptor space() { return delegate.space(); }
            public EmbeddingLocality locality() { return delegate.locality(); }
            public List<float[]> embedAll(List<String> inputs) throws EmbeddingException {
                if (failNext.getAndSet(false)) throw new EmbeddingException("remote_transient", "temporary test failure");
                return delegate.embedAll(inputs);
            }
        };
        var now = new AtomicLong(100000);
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        var config = new CodeAgentConfig.AutoIndexConfig(); config.setDebounceMillis(0);
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"));
             var service = new DefaultCodeRetrievalService(index, resolution(flaky));
             var manager = new WorkspaceCodeIndexManager(service, config, clock, false)) {
            manager.register(root); manager.awaitIdle(root, Duration.ofSeconds(5));
            for (int round = 0; round < 3; round++) {
                failNext.set(true);
                Files.writeString(source, "class Store { void action" + round + "() {} }");
                manager.pathChanged(root, source);
                eventually(() -> manager.status(root).errorCode().equals("remote_transient"));
                now.addAndGet(10000);
                manager.awaitIdle(root, Duration.ofSeconds(5));
                assertEquals("idle", manager.status(root).state());
                assertTrue(manager.status(root).errorCode().isEmpty());
            }
        }
    }
    @Test void malformedVectorBatchHasDiagnosticAndBoundedRetries(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Files.writeString(root.resolve("Store.java"), "class Store { void saveData() {} }");
        var calls = new AtomicInteger();
        var delegate = provider(EmbeddingLocality.IN_PROCESS, () -> {});
        var malformed = new EmbeddingProvider() {
            public String id() { return delegate.id(); }
            public String modelId() { return delegate.modelId(); }
            public EmbeddingSpaceDescriptor space() { return delegate.space(); }
            public EmbeddingLocality locality() { return delegate.locality(); }
            public List<float[]> embedAll(List<String> inputs) { calls.incrementAndGet(); return List.of(); }
        };
        var now = new AtomicLong(100000);
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        var config = new CodeAgentConfig.AutoIndexConfig();
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"));
             var service = new DefaultCodeRetrievalService(index, resolution(malformed));
             var manager = new WorkspaceCodeIndexManager(service, config, clock, false)) {
            manager.register(root);
            eventually(() -> !manager.status(root).errorCode().isBlank());
            assertEquals(1, calls.get());
            now.addAndGet(5000); eventually(() -> calls.get() == 2 && manager.status(root).pendingEmbedding() == 0);
            now.addAndGet(10000); eventually(() -> manager.status(root).state().equals("degraded"));
            assertEquals(3, calls.get());
            assertTrue(terms(index, root, "saveData"));
        }
    }
    @Test void lexicalUpdatesWhileOldVectorIsBlocked(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Path source = root.resolve("Store.java");
        Files.writeString(source, "class Store { void oldAction() {} }");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var provider = provider(EmbeddingLocality.IN_PROCESS, () -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); await(release); }
        });
        var options = new CodeAgentConfig.AutoIndexConfig(); options.setDebounceMillis(0);
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"));
             var service = new DefaultCodeRetrievalService(index, resolution(provider));
             var manager = new WorkspaceCodeIndexManager(service, options, Clock.systemUTC(), false)) {
            manager.register(root);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Files.writeString(source, "class Store { void newAction() {} }");
                manager.pathChanged(root, source);
                eventually(() -> terms(index, root, "newAction"));
                assertFalse(terms(index, root, "oldAction"));
            } finally { release.countDown(); }
            manager.awaitIdle(root, Duration.ofSeconds(8));
            assertFalse(index.searchVector(root, provider.space().embeddingSpaceId(), new float[]{1,0}, 10).isEmpty());
            assertTrue(calls.get() >= 2);
        } finally { release.countDown(); }
    }

    @Test void remoteConsentIsProjectScopedAndRecheckedBeforeCommit(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Path other = Files.createDirectory(temp.resolve("other"));
        Files.writeString(root.resolve("Store.java"), "class Store { void saveData() {} }");
        Files.writeString(other.resolve("Other.java"), "class Other { void privateOtherData() {} }");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var consent = new AtomicBoolean(false); var calls = new AtomicInteger();
        var provider = provider(EmbeddingLocality.REMOTE, () -> { calls.incrementAndGet(); entered.countDown(); await(release); });
        var executor = Executors.newSingleThreadExecutor();
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"));
             var service = new DefaultCodeRetrievalService(index, resolution(provider))) {
            service.reconcileLexical(new IndexRefreshRequest(root, false));
            service.reconcileLexical(new IndexRefreshRequest(other, false));
            assertTrue(terms(index, other, "privateOtherData"));
            assertTrue(service.missingEmbeddingWork(root, 10).isEmpty());
            assertEquals(0, calls.get());
            consent.set(true);
            service.reconfigureEmbeddingForProject(resolution(provider), root, consent::get);
            assertTrue(service.missingEmbeddingWork(other, 10).isEmpty());
            var work = service.missingEmbeddingWork(root, 10).get(0);
            var result = executor.submit(() -> service.backfill(work, () -> true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            consent.set(false); release.countDown();
            assertFalse(result.get(5, TimeUnit.SECONDS));
            assertTrue(index.searchVector(root, provider.space().embeddingSpaceId(), new float[]{1,0}, 10).isEmpty());
            assertTrue(terms(index, root, "saveData"));
            assertEquals(1, calls.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void periodicReconciliationFindsChangesWithoutNotifications(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Path source = root.resolve("Store.java");
        Files.writeString(source, "class Store { void oldAction() {} }");
        var options = new CodeAgentConfig.AutoIndexConfig(); options.setReconcileIntervalSeconds(1);
        var now = new AtomicLong(100000);
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"));
             var service = new DefaultCodeRetrievalService(index, new EmbeddingResolution(Optional.empty(), "off", false));
             var manager = new WorkspaceCodeIndexManager(service, options, clock, false)) {
            manager.register(root); manager.awaitIdle(root, Duration.ofSeconds(5));
            Files.writeString(source, "class Store { void newAction() {} }");
            now.addAndGet(2000);
            eventually(() -> terms(index, root, "newAction"));
            assertFalse(terms(index, root, "oldAction"));
        }
    }

    private static EmbeddingResolution resolution(EmbeddingProvider provider) {
        return new EmbeddingResolution(Optional.of(provider), "test", false);
    }
    private static EmbeddingProvider provider(EmbeddingLocality locality, Runnable before) {
        return new EmbeddingProvider() {
            final EmbeddingSpaceDescriptor space = EmbeddingSpaceDescriptor.create("test", "test", "test", "1", 2, "mean", true, 1, 1);
            public String id() { return "test"; }
            public String modelId() { return "test"; }
            public EmbeddingSpaceDescriptor space() { return space; }
            public EmbeddingLocality locality() { return locality; }
            public List<float[]> embedAll(List<String> inputs) { before.run(); return inputs.stream().map(s -> new float[]{1,0}).toList(); }
        };
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static boolean terms(SqliteRetrievalIndex index, Path root, String query) {
        try { return !index.searchTerms(root, query, 10).isEmpty(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void eventually(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline)
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        assertTrue(condition.getAsBoolean(), "condition never became true");
    }
}
