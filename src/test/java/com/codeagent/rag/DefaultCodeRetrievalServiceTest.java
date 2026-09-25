package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingException;
import com.codeagent.rag.embedding.EmbeddingLocality;
import com.codeagent.rag.embedding.EmbeddingProvider;
import com.codeagent.rag.embedding.EmbeddingResolution;
import com.codeagent.rag.embedding.EmbeddingSpaceDescriptor;
import com.codeagent.search.JavaCodeSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultCodeRetrievalServiceTest {
    @Test
    void worksWithoutIndexThroughLiveFallback(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.writeString(root.resolve("Router.java"), "class Router { void routeRequest() {} }");
        try (DefaultCodeRetrievalService service = service(temp, Optional.empty())) {
            RetrievalResponse response = service.search(new RetrievalRequest(root,
                    "Router routeRequest", 5, 4000, true, RetrievalIntent.CHUNKS));
            assertFalse(response.hits().isEmpty());
            assertTrue(response.hits().get(0).sources().contains(RetrievalSource.LIVE_GREP));
        }
    }

    @Test
    void semanticFailureReturnsLexicalHitsAndMarksDegraded(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.writeString(root.resolve("Context.java"), "class Context { void compactHistory() {} }");
        try (DefaultCodeRetrievalService service = service(temp, Optional.of(failingProvider()))) {
            service.refresh(new IndexRefreshRequest(root, false));
            RetrievalResponse response = service.search(new RetrievalRequest(root,
                    "compactHistory", 5, 4000, false, RetrievalIntent.CHUNKS));
            assertFalse(response.hits().isEmpty());
            assertTrue(response.partial());
            assertTrue(response.diagnostics().degradedReasonCodes().stream()
                    .anyMatch(reason -> reason.contains("semantic")));
        }
    }

    @Test
    void architectureIntentIncludesBudgetedRepositoryMap(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.writeString(root.resolve("Router.java"), "class Router { void routeRequest() {} }");
        try (DefaultCodeRetrievalService service = service(temp, Optional.empty())) {
            service.refresh(new IndexRefreshRequest(root, false));
            RetrievalResponse response = service.search(new RetrievalRequest(root,
                    "Router", 5, 4000, false, RetrievalIntent.ARCHITECTURE));
            assertTrue(response.repositoryMap().isPresent());
            assertTrue(response.repositoryMap().orElseThrow().text().contains("Router"));
        }
    }

    private static DefaultCodeRetrievalService service(Path temp,
            Optional<EmbeddingProvider> provider) throws Exception {
        return new DefaultCodeRetrievalService(new SqliteRetrievalIndex(temp.resolve("v2.db")),
                new JavaCodeSearchService(Set.of("target")),
                new EmbeddingResolution(provider, "test", false));
    }

    private static EmbeddingProvider failingProvider() {
        EmbeddingSpaceDescriptor space = EmbeddingSpaceDescriptor.create(
                "fail", "fail", "in-process", "1", 2, "test", true, 1, 1);
        return new EmbeddingProvider() {
            @Override public String id() { return "fail"; }
            @Override public String modelId() { return "fail"; }
            @Override public EmbeddingSpaceDescriptor space() { return space; }
            @Override public EmbeddingLocality locality() { return EmbeddingLocality.IN_PROCESS; }
            @Override public List<float[]> embedAll(List<String> inputs) throws EmbeddingException {
                throw new EmbeddingException("timeout", "failed");
            }
        };
    }
}
