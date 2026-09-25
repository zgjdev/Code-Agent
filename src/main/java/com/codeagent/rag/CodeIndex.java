package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingException;
import com.codeagent.rag.embedding.EmbeddingLocality;
import com.codeagent.rag.embedding.EmbeddingProvider;
import com.codeagent.rag.embedding.EmbeddingSpaceDescriptor;
import com.codeagent.rag.embedding.InProcessBgeEmbeddingProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Compatibility facade over the incremental v2 retrieval index. */
public class CodeIndex {
    private final Optional<EmbeddingProvider> provider;
    private final ProgressListener progressListener;

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message);
        static ProgressListener noop() { return ignored -> {}; }
    }

    public CodeIndex() {
        this(Optional.of(new InProcessBgeEmbeddingProvider()), ProgressListener.noop());
    }

    public CodeIndex(ProgressListener progressListener) {
        this(Optional.of(new InProcessBgeEmbeddingProvider()), progressListener);
    }

    /** Retained for source compatibility while callers migrate to EmbeddingProvider. */
    @Deprecated
    public CodeIndex(EmbeddingClient client) {
        this(client, ProgressListener.noop());
    }

    /** Retained for source compatibility while callers migrate to EmbeddingProvider. */
    @Deprecated
    public CodeIndex(EmbeddingClient client, ProgressListener progressListener) {
        this(Optional.of(new ClientAdapter(client)), progressListener);
    }

    public CodeIndex(Optional<EmbeddingProvider> provider, ProgressListener progressListener) {
        this.provider = provider == null ? Optional.empty() : provider;
        this.progressListener = progressListener == null ? ProgressListener.noop() : progressListener;
    }

    public IndexResult index(String projectPath) {
        Path root = Path.of(projectPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            String message = "路径不存在: " + projectPath;
            emit("❌ " + message);
            return new IndexResult(0, 0, message);
        }
        emit("🔍 开始索引: " + root);
        IndexFileScanner.ScanResult scan = new IndexFileScanner().scan(root);
        emit("📁 发现 " + scan.files().size() + " 个文件待索引");
        Path directory = Path.of(System.getProperty("codeagent.rag.dir",
                Path.of(System.getProperty("user.home"), ".codeagent", "rag").toString()));
        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(
                directory.resolve("codebase-v2.db"), directory.resolve("codebase.db"))) {
            IndexCoordinator coordinator = new IndexCoordinator(index, provider);
            IndexRefreshResult result = coordinator.refresh(new IndexRefreshRequest(root, false));
            RetrievalIndexStatus status = index.status(root);
            String message = String.format("索引完成：%d 个代码块，变更 %d，未变 %d，删除 %d，失败 %d",
                    status.chunkCount(), result.changedFiles(), result.unchangedFiles(),
                    result.deletedFiles(), result.failedFiles());
            emit("✅ " + message);
            return new IndexResult(status.chunkCount(), 0, message);
        } catch (Exception e) {
            String message = "持久化失败: " + e.getClass().getSimpleName();
            emit("❌ " + message);
            return new IndexResult(0, 0, message);
        }
    }

    private void emit(String message) { progressListener.onProgress(message); }

    public record IndexResult(int chunkCount, int relationCount, String message) {}

    private static final class ClientAdapter implements EmbeddingProvider {
        private final EmbeddingClient client;
        private final EmbeddingSpaceDescriptor space;

        private ClientAdapter(EmbeddingClient client) {
            this.client = client;
            int dimension = InProcessBgeEmbeddingProvider.PROVIDER_ID.equals(client.getProvider()) ? 512 : 2;
            this.space = EmbeddingSpaceDescriptor.create("legacy-adapter", client.getModel(),
                    "in-process", "compatibility", dimension, "provider-defined", true, 1, 1);
        }

        @Override public String id() { return "legacy-adapter"; }
        @Override public String modelId() { return client.getModel(); }
        @Override public EmbeddingSpaceDescriptor space() { return space; }
        @Override public EmbeddingLocality locality() { return EmbeddingLocality.IN_PROCESS; }

        @Override
        public List<float[]> embedAll(List<String> inputs) throws EmbeddingException {
            List<float[]> result = new ArrayList<>(inputs.size());
            try {
                for (String input : inputs) result.add(client.embed(input));
                return List.copyOf(result);
            } catch (Exception e) {
                throw new EmbeddingException("legacy_embedding_failed", "Embedding failed", e);
            }
        }

        @Override public void close() throws Exception { client.close(); }
    }
}
