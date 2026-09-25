package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingException;
import com.codeagent.rag.embedding.EmbeddingLocality;
import com.codeagent.rag.embedding.EmbeddingProvider;
import com.codeagent.rag.embedding.EmbeddingSpaceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexCoordinatorTest {
    @Test
    void embeddingOffStillBuildsLexicalStructureAndIncrementallyDeletes(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Path source = root.resolve("ContextService.java");
        Files.writeString(source, "class ContextService { void compactHistory() {} }");
        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(temp.resolve("codebase-v2.db"))) {
            IndexCoordinator coordinator = new IndexCoordinator(index, Optional.empty());

            IndexRefreshResult first = coordinator.refresh(new IndexRefreshRequest(root, false));
            assertEquals(1, first.changedFiles());
            assertFalse(index.searchTerms(root, "ContextService", 10).isEmpty());
            assertFalse(index.searchTrigram(root, "compactHistory", 10).isEmpty());
            assertFalse(index.searchSymbols(root, "ContextService", 10).isEmpty());

            IndexRefreshResult second = coordinator.refresh(new IndexRefreshRequest(root, false));
            assertEquals(1, second.unchangedFiles());
            assertEquals(0, second.changedFiles());

            Files.delete(source);
            IndexRefreshResult third = coordinator.refresh(new IndexRefreshRequest(root, false));
            assertEquals(1, third.deletedFiles());
            assertTrue(index.searchTerms(root, "ContextService", 10).isEmpty());
        }
    }

    @Test
    void providerFailureDoesNotRollbackLexicalAndLaterRefreshBackfillsVectors(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.writeString(root.resolve("Agent.java"), "class Agent { void routeRequest() {} }");
        EmbeddingSpaceDescriptor space = EmbeddingSpaceDescriptor.create(
                "test", "test", "in-process", "1", 2, "test", true, 1, 1);
        EmbeddingProvider failing = provider(space, true);
        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(temp.resolve("codebase-v2.db"))) {
            IndexRefreshResult failed = new IndexCoordinator(index, Optional.of(failing))
                    .refresh(new IndexRefreshRequest(root, false));

            assertEquals(1, failed.changedFiles());
            assertTrue(failed.reasonCodes().contains("embedding_failed"));
            assertFalse(index.searchTerms(root, "routeRequest", 10).isEmpty());
            assertTrue(index.searchVector(root, space.embeddingSpaceId(), new float[]{1, 1}, 10).isEmpty());

            IndexRefreshResult retried = new IndexCoordinator(index, Optional.of(provider(space, false)))
                    .refresh(new IndexRefreshRequest(root, false));
            assertEquals(1, retried.unchangedFiles());
            assertFalse(index.searchVector(root, space.embeddingSpaceId(), new float[]{1, 1}, 10).isEmpty());
        }
    }

    private static EmbeddingProvider provider(EmbeddingSpaceDescriptor space, boolean fail) {
        return new EmbeddingProvider() {
            @Override public String id() { return "test"; }
            @Override public String modelId() { return "test"; }
            @Override public EmbeddingSpaceDescriptor space() { return space; }
            @Override public EmbeddingLocality locality() { return EmbeddingLocality.IN_PROCESS; }
            @Override public List<float[]> embedAll(List<String> inputs) throws EmbeddingException {
                if (fail) throw new EmbeddingException("test_failure", "failed");
                return inputs.stream().map(ignored -> new float[]{1, 1}).toList();
            }
        };
    }
}
