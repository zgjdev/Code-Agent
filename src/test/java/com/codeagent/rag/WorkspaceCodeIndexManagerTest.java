package com.codeagent.rag;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.rag.embedding.EmbeddingResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceCodeIndexManagerTest {
    @Test void startupChangesDeletionAndRecreationAreIndexedAutomatically(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Path source = root.resolve("Store.java");
        Files.writeString(source, "class Store { void saveData() {} }");
        var config = new CodeAgentConfig.AutoIndexConfig();
        config.setDebounceMillis(0);
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"));
             var service = new DefaultCodeRetrievalService(index, new EmbeddingResolution(Optional.empty(), "off", false));
             var manager = new WorkspaceCodeIndexManager(service, config, Clock.systemUTC(), false)) {
            manager.register(root);
            manager.awaitIdle(root, Duration.ofSeconds(5));
            assertFalse(index.searchTerms(root, "saveData", 10).isEmpty());
            Files.writeString(source, "class Store { void replaceData() {} }");
            manager.pathChanged(root, source);
            manager.awaitIdle(root, Duration.ofSeconds(5));
            assertTrue(index.searchTerms(root, "saveData", 10).isEmpty());
            assertFalse(index.searchTerms(root, "replaceData", 10).isEmpty());
            Files.delete(source);
            manager.pathChanged(root, source);
            manager.awaitIdle(root, Duration.ofSeconds(5));
            assertEquals(0, index.status(root).indexedFileCount());
            Files.writeString(source, "class Store { void saveData() {} }");
            manager.pathChanged(root, source);
            manager.awaitIdle(root, Duration.ofSeconds(5));
            assertFalse(index.searchTerms(root, "saveData", 10).isEmpty());
        }
    }

    @Test void newDirectoryIsIndexedByRealWatcher(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        var config = new CodeAgentConfig.AutoIndexConfig(); config.setDebounceMillis(0);
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"));
             var service = new DefaultCodeRetrievalService(index, new EmbeddingResolution(Optional.empty(), "off", false));
             var manager = new WorkspaceCodeIndexManager(service, config, Clock.systemUTC(), true)) {
            manager.register(root);
            manager.awaitIdle(root, Duration.ofSeconds(5));
            Path child = Files.createDirectory(root.resolve("nested"));
            Files.writeString(child.resolve("Fresh.java"), "class Fresh { void freshOperation() {} }");
            // Actual OS watcher smoke test; deterministic scheduler tests use explicit notifications.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while (index.searchTerms(root, "freshOperation", 10).isEmpty() && System.nanoTime() < deadline)
                Thread.sleep(20);
            assertFalse(index.searchTerms(root, "freshOperation", 10).isEmpty());
        }
    }
}
