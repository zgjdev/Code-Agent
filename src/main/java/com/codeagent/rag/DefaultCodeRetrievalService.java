package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingProvider;
import com.codeagent.rag.embedding.EmbeddingResolution;
import com.codeagent.rag.stage.CodeRetrieverStage;
import com.codeagent.rag.stage.GraphRetriever;
import com.codeagent.rag.stage.LiveGrepRetriever;
import com.codeagent.rag.stage.SemanticRetriever;
import com.codeagent.rag.stage.SymbolRetriever;
import com.codeagent.rag.stage.TermFtsRetriever;
import com.codeagent.rag.stage.TrigramFtsRetriever;
import com.codeagent.search.CodeSearchService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class DefaultCodeRetrievalService implements CodeRetrievalService {
    private final SqliteRetrievalIndex index;
    private final CodeSearchService codeSearchService;
    private final List<CodeRetrieverStage> stages;
    private final ReentrantReadWriteLock providerLock = new ReentrantReadWriteLock();
    private EmbeddingResolution embeddingResolution;
    private volatile Path lastProjectRoot;
    private volatile boolean closed;

    public DefaultCodeRetrievalService(SqliteRetrievalIndex index,
            CodeSearchService codeSearchService, EmbeddingResolution embeddingResolution) {
        this.index = index;
        this.codeSearchService = codeSearchService;
        this.embeddingResolution = embeddingResolution == null
                ? new EmbeddingResolution(Optional.empty(), "embedding_disabled", false)
                : embeddingResolution;
        this.stages = List.of(new LiveGrepRetriever(), new TermFtsRetriever(),
                new TrigramFtsRetriever(), new SymbolRetriever(), new GraphRetriever(),
                new SemanticRetriever());
    }

    @Override
    public RetrievalResponse search(RetrievalRequest request) {
        ensureOpen();
        lastProjectRoot = request.projectRoot();
        providerLock.readLock().lock();
        try {
            RetrievalContext context = new RetrievalContext(request, index,
                    embeddingResolution.provider(), codeSearchService);
            RetrievalStageRunner.Result stagesResult = new RetrievalStageRunner().run(stages, context);
            List<RetrievalHit> fused = new RetrievalFusion().fuse(
                    stagesResult.rankings(), request.query(), Math.max(request.topK() * 3, 15));
            RetrievalBudget.Result budgeted = new RetrievalBudget().apply(
                    fused, request.topK(), request.maxChars());
            List<String> reasons = new ArrayList<>(stagesResult.degradedReasonCodes());
            Optional<RepositoryMap> repositoryMap = request.intent() == RetrievalIntent.ARCHITECTURE
                    ? Optional.of(new RepositoryMapSelector(index).select(
                            request.projectRoot(), request.query(), 1_500))
                    : Optional.empty();
            boolean partial = budgeted.partial() || !reasons.isEmpty()
                    || repositoryMap.map(RepositoryMap::partial).orElse(false);
            String providerId = embeddingResolution.provider().map(EmbeddingProvider::id).orElse("off");
            RetrievalDiagnostics diagnostics = new RetrievalDiagnostics(providerId,
                    stagesResult.durations(), stagesResult.hits(), reasons,
                    SqliteRetrievalIndex.SCHEMA_VERSION);
            return new RetrievalResponse(budgeted.hits(), repositoryMap, diagnostics, partial);
        } finally {
            providerLock.readLock().unlock();
        }
    }

    @Override
    public IndexRefreshResult refresh(IndexRefreshRequest request) {
        ensureOpen();
        lastProjectRoot = request.projectRoot();
        providerLock.readLock().lock();
        try {
            return new IndexCoordinator(index, embeddingResolution.provider()).refresh(request);
        } finally {
            providerLock.readLock().unlock();
        }
    }

    public void clear(Path projectRoot) {
        ensureOpen();
        lastProjectRoot = projectRoot;
        providerLock.readLock().lock();
        try {
            new IndexCoordinator(index, Optional.empty()).clear(projectRoot);
        } finally {
            providerLock.readLock().unlock();
        }
    }

    @Override
    public RetrievalIndexStatus status() {
        if (closed || lastProjectRoot == null) {
            return new RetrievalIndexStatus(!closed, false, 0, 0);
        }
        try {
            return index.status(lastProjectRoot);
        } catch (Exception e) {
            return new RetrievalIndexStatus(false, false, 0, 0);
        }
    }

    @Override
    public void reconfigureEmbedding(EmbeddingResolution resolution) {
        ensureOpen();
        providerLock.writeLock().lock();
        try {
            EmbeddingResolution previous = embeddingResolution;
            embeddingResolution = resolution == null
                    ? new EmbeddingResolution(Optional.empty(), "embedding_disabled", false)
                    : resolution;
            previous.provider().ifPresent(provider -> {
                if (embeddingResolution.provider().orElse(null) != provider) {
                    try { provider.close(); } catch (Exception ignored) {}
                }
            });
        } finally {
            providerLock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        providerLock.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            embeddingResolution.provider().ifPresent(provider -> {
                try { provider.close(); } catch (Exception ignored) {}
            });
            try { index.close(); } catch (Exception ignored) {}
        } finally {
            providerLock.writeLock().unlock();
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Retrieval service is closed");
    }
}
