package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingInputPolicy;
import com.codeagent.rag.embedding.EmbeddingProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Coordinates lexical file transactions and independent embedding backfill transactions.
 */
public final class IndexCoordinator {

    private final SqliteRetrievalIndex index;

    private final Optional<EmbeddingProvider> embeddingProvider;

    private final IndexFileScanner scanner;

    private final CodeChunker chunker;

    private final CodeAnalyzer analyzer;

    private final LexicalTextNormalizer normalizer;

    private final EmbeddingInputPolicy inputPolicy;

    public IndexCoordinator(SqliteRetrievalIndex index, Optional<EmbeddingProvider> embeddingProvider) {
        this(index, embeddingProvider, new IndexFileScanner(), new CodeChunker(), new CodeAnalyzer(), new LexicalTextNormalizer(), new EmbeddingInputPolicy());
    }

    IndexCoordinator(SqliteRetrievalIndex index, Optional<EmbeddingProvider> embeddingProvider, IndexFileScanner scanner, CodeChunker chunker, CodeAnalyzer analyzer, LexicalTextNormalizer normalizer, EmbeddingInputPolicy inputPolicy) {
        this.index = index;
        this.embeddingProvider = embeddingProvider == null ? Optional.empty() : embeddingProvider;
        this.scanner = scanner;
        this.chunker = chunker;
        this.analyzer = analyzer;
        this.normalizer = normalizer;
        this.inputPolicy = inputPolicy;
    }

    private Object commitGate = this;
    private java.util.function.BooleanSupplier commitAllowed = () -> true;

    public IndexCoordinator withCommitGuard(Object gate, java.util.function.BooleanSupplier allowed) {
        this.commitGate = java.util.Objects.requireNonNull(gate);
        this.commitAllowed = java.util.Objects.requireNonNull(allowed);
        return this;
    }

    private boolean mutate(IndexMutation mutation) throws java.sql.SQLException {
        synchronized (commitGate) {
            if (!commitAllowed.getAsBoolean()) return false;
            mutation.run();
            return true;
        }
    }

    @FunctionalInterface
    private interface IndexMutation { void run() throws java.sql.SQLException; }

    public IndexRefreshResult refresh(IndexRefreshRequest request) {
        IndexRefreshResult lexical = reconcileLexical(request);
        Set<String> reasons = new LinkedHashSet<>(lexical.reasonCodes());
        if (embeddingProvider.isPresent())
            for (EmbeddingWorkItem work : missingEmbeddingWork(request.projectRoot(), Integer.MAX_VALUE, lexical.embeddingReadyPaths())) {
                try {
                    commitEmbeddings(computeEmbeddings(work));
                } catch (Exception e) {
                    reasons.add("embedding_failed");
                }
            }
        return new IndexRefreshResult(lexical.changedFiles(), lexical.unchangedFiles(), lexical.deletedFiles(), lexical.failedFiles(),
                List.copyOf(reasons), lexical.embeddingReadyPaths());
    }

    public IndexRefreshResult rebuild(IndexRefreshRequest request) {
        return refresh(new IndexRefreshRequest(request.projectRoot(), true));
    }

    public void clear(Path projectRoot) {
        try {
            Path root = canonicalRoot(projectRoot);
            if (!mutate(() -> index.clearProject(root))) throw new IllegalStateException("Index maintenance closed");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to clear retrieval index", e);
        }
    }

    public IndexRefreshResult reconcileLexical(IndexRefreshRequest request) {
        return execute(request, request.rebuild());
    }

    public IndexRefreshResult refreshPaths(Path root, List<Path> paths) {
        int changed = 0, unchanged = 0, deleted = 0, failed = 0;
        Set<String> reasons = new LinkedHashSet<>();
        Set<Path> ready = new HashSet<>();
        for (Path path : paths) {
            IndexRefreshResult result = refreshLexicalPath(root, path);
            changed += result.changedFiles();
            unchanged += result.unchangedFiles();
            deleted += result.deletedFiles();
            failed += result.failedFiles();
            reasons.addAll(result.reasonCodes());
            ready.addAll(result.embeddingReadyPaths());
        }
        return new IndexRefreshResult(changed, unchanged, deleted, failed, List.copyOf(reasons), ready);
    }

    public IndexRefreshResult refreshLexicalPath(Path requestedRoot, Path path) {
        try {
            IndexPathPolicy policy = new IndexPathPolicy(requestedRoot);
            Path absolute = policy.resolve(path);
            if (!absolute.startsWith(policy.root()))
                return new IndexRefreshResult(0, 0, 0, 0, List.of("path_not_eligible"));
            Path relative = policy.root().relativize(absolute);
            Optional<FileSnapshot> old = index.findFile(policy.root(), relative);
            if (!policy.isEligible(absolute)) {
                // Remove only confirmed absent or deliberately excluded paths; unreadable files are not deletion.
                if (Files.notExists(absolute, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.exists(absolute, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (old.isPresent())
                        if (!mutate(() -> index.deleteFile(policy.root(), relative))) return new IndexRefreshResult(0,0,0,1,List.of("maintenance_closed"));
                    return new IndexRefreshResult(0, 0, old.isPresent() ? 1 : 0, 0, List.of());
                }
                return new IndexRefreshResult(0, 0, 0, 1, List.of("file_unreadable"));
            }
            FileContentSnapshot snapshot = FileContentSnapshot.read(policy, absolute);
            if (old.isPresent() && old.get().contentHash().equals(snapshot.contentHash()))
                return new IndexRefreshResult(0, 1, 0, 0, List.of(), Set.of(relative));
            var file = new IndexFileScanner.ScannedFile(absolute, relative, snapshot.contentHash(), snapshot.sizeBytes(), snapshot.modifiedMillis(), snapshot.language());
            FileIndexBatch batch = buildLexicalBatch(policy.root(), file, snapshot);
            if (!snapshot.stillCurrent(policy))
                throw new java.io.IOException("File changed before commit");
            if (!mutate(() -> index.replaceLexicalFile(batch))) return new IndexRefreshResult(0,0,0,1,List.of("maintenance_closed"));
            return new IndexRefreshResult(1, 0, 0, 0, List.of(), Set.of(relative));
        } catch (Exception e) {
            return new IndexRefreshResult(0, 0, 0, 1, List.of("lexical_index_failed"));
        }
    }

    public List<EmbeddingWorkItem> missingEmbeddingWork(Path root, int limit) {
        if (embeddingProvider.isEmpty())
            return List.of();
        return pendingEmbeddingFiles(root, embeddingProvider.get().space().embeddingSpaceId(), limit);
    }

    public List<EmbeddingWorkItem> missingEmbeddingWork(Path root, int limit, Set<Path> eligiblePaths) {
        if (embeddingProvider.isEmpty()) return List.of();
        return pendingEmbeddingFiles(root, embeddingProvider.get().space().embeddingSpaceId(), limit, eligiblePaths::contains);
    }

    public List<EmbeddingWorkItem> pendingEmbeddingFiles(Path requestedRoot, String spaceId, int limit) {
        return pendingEmbeddingFiles(requestedRoot, spaceId, limit, ignored -> true);
    }

    private List<EmbeddingWorkItem> pendingEmbeddingFiles(Path requestedRoot, String spaceId, int limit,
                                                         java.util.function.Predicate<Path> eligible) {
        if (limit <= 0)
            return List.of();
        try {
            Path root = canonicalRoot(requestedRoot);
            List<EmbeddingWorkItem> work = new ArrayList<>();
            synchronized (index) {
                for (FileSnapshot file : index.listFiles(root)) {
                    Path relative = Path.of(file.filePath());
                    if (!eligible.test(relative)) continue;
                    int count = index.countChunks(root, relative);
                    if (!index.hasCompleteEmbeddings(root, relative, spaceId, count))
                        work.add(new EmbeddingWorkItem(root, relative, file.contentHash(), spaceId));
                    if (work.size() >= limit)
                        break;
                }
            }
            return List.copyOf(work);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to gather embedding work", e);
        }
    }

    public FileEmbeddingBatch computeEmbeddings(EmbeddingWorkItem work) throws Exception {
        EmbeddingProvider provider = embeddingProvider.orElseThrow();
        if (!provider.space().embeddingSpaceId().equals(work.embeddingSpaceId()))
            throw new StaleIndexWorkException("Embedding space changed");
        IndexPathPolicy policy = new IndexPathPolicy(work.projectRoot());
        if (Files.notExists(policy.resolve(work.relativePath()), java.nio.file.LinkOption.NOFOLLOW_LINKS) || !policy.isEligible(work.relativePath()))
            throw new StaleIndexWorkException("File no longer exists");
        FileContentSnapshot snapshot = FileContentSnapshot.read(policy, work.relativePath());
        if (!snapshot.contentHash().equals(work.expectedContentHash()))
            throw new StaleIndexWorkException("Stale file version");
        List<IndexedChunk> chunks;
        synchronized (index) {
            FileSnapshot current = index.findFile(work.projectRoot(), work.relativePath()).orElseThrow(() -> new StaleIndexWorkException("Indexed file no longer exists"));
            if (!current.contentHash().equals(work.expectedContentHash()))
                throw new StaleIndexWorkException("Stale indexed version");
            chunks = index.listChunks(work.projectRoot(), work.relativePath());
        }
        FileEmbeddingBatch batch = embed(work.projectRoot(), work.relativePath(), chunks, provider);
        return new FileEmbeddingBatch(batch.projectRoot(), batch.relativePath(), batch.space(), batch.embeddings(), work.expectedContentHash());
    }

    public boolean commitEmbeddings(FileEmbeddingBatch batch) throws Exception {
        IndexPathPolicy policy = new IndexPathPolicy(batch.projectRoot());
        if (batch.expectedFileContentHash() == null || !policy.isEligible(batch.relativePath()) || !FileContentSnapshot.read(policy, batch.relativePath()).contentHash().equals(batch.expectedFileContentHash()))
            return false;
        synchronized (commitGate) {
            return commitAllowed.getAsBoolean() && index.replaceFileEmbeddingsIfCurrent(batch);
        }
    }

    private IndexRefreshResult execute(IndexRefreshRequest request, boolean rebuild) {
        int changed = 0;
        int unchanged = 0;
        int deleted = 0;
        int failed = 0;
        Set<String> reasons = new LinkedHashSet<>();
        Set<Path> ready = new HashSet<>();
        IndexFileScanner.ScanResult scan = scanner.scan(request.projectRoot());
        if (!scan.complete())
            reasons.add("scan_incomplete");
        if (scan.files().isEmpty() && !scan.complete()) {
            return new IndexRefreshResult(0, 0, 0, scan.failures().size(), List.copyOf(reasons));
        }
        try {
            Path root = canonicalRoot(request.projectRoot());
            Map<String, FileSnapshot> previous = new HashMap<>();
            for (FileSnapshot snapshot : index.listFiles(root)) previous.put(snapshot.filePath(), snapshot);
            Set<String> seen = new HashSet<>();
            for (IndexFileScanner.ScannedFile file : scan.files()) {
                String key = normalize(file.relativePath());
                seen.add(key);
                FileSnapshot old = previous.get(key);
                boolean lexicalChanged = rebuild || old == null || !old.contentHash().equals(file.contentHash());
                if (lexicalChanged) {
                    try {
                        FileIndexBatch batch = buildLexicalBatch(root, file);
                        if (!FileContentSnapshot.read(new IndexPathPolicy(root), file.absolutePath()).contentHash().equals(batch.contentHash()))
                            throw new java.io.IOException("File changed before commit");
                        if (!mutate(() -> index.replaceLexicalFile(batch))) {
                            reasons.add("maintenance_closed");
                            return new IndexRefreshResult(changed, unchanged, deleted, failed, List.copyOf(reasons), ready);
                        }
                        changed++;
                    } catch (Exception e) {
                        failed++;
                        reasons.add("lexical_index_failed");
                        continue;
                    }
                } else {
                    unchanged++;
                }
                ready.add(file.relativePath());
            }
            if (scan.complete()) {
                for (FileSnapshot old : previous.values()) {
                    if (!seen.contains(old.filePath())) {
                        if (!mutate(() -> index.deleteFile(root, Path.of(old.filePath())))) {
                            reasons.add("maintenance_closed");
                            return new IndexRefreshResult(changed, unchanged, deleted, failed, List.copyOf(reasons), ready);
                        }
                        deleted++;
                    }
                }
            }
        } catch (Exception e) {
            failed++;
            reasons.add("index_refresh_failed");
        }
        failed += scan.failures().size();
        return new IndexRefreshResult(changed, unchanged, deleted, failed, List.copyOf(reasons), ready);
    }

    private FileIndexBatch buildLexicalBatch(Path root, IndexFileScanner.ScannedFile file) throws Exception {
        FileContentSnapshot snapshot = FileContentSnapshot.read(new IndexPathPolicy(root), file.absolutePath());
        if (!snapshot.contentHash().equals(file.contentHash()))
            throw new java.io.IOException("File changed after scanning");
        return buildLexicalBatch(root, file, snapshot);
    }

    private FileIndexBatch buildLexicalBatch(Path root, IndexFileScanner.ScannedFile file, FileContentSnapshot snapshot) throws Exception {
        List<CodeChunk> rawChunks = chunker.chunkContent(file.absolutePath(), snapshot.content());
        String relative = normalize(file.relativePath());
        String fileSymbolId = StableSymbolId.create(relative, "FILE", relative, "");
        List<IndexedSymbol> symbols = new ArrayList<>();
        symbols.add(new IndexedSymbol(fileSymbolId, relative, file.relativePath().getFileName().toString(), "", "FILE", null, 1, Math.max(1, snapshot.content().split("\\R", -1).length)));
        Map<String, String> symbolIds = new HashMap<>();
        symbolIds.put("file", fileSymbolId);
        List<IndexedChunk> chunks = new ArrayList<>();
        for (CodeChunk chunk : rawChunks) {
            String kind = chunk.chunkType().toUpperCase();
            String qualified = chunk.name();
            String signature = "METHOD".equals(kind) ? chunk.name() : "";
            String symbolId = "FILE".equals(kind) ? fileSymbolId : StableSymbolId.create(relative, kind, qualified, signature);
            if (!"FILE".equals(kind)) {
                String simple = simpleName(qualified);
                String owner = "METHOD".equals(kind) ? ownerId(symbols, qualified) : fileSymbolId;
                symbols.add(new IndexedSymbol(symbolId, qualified, simple, signature, kind, owner, chunk.startLine(), chunk.endLine()));
                symbolIds.put(qualified, symbolId);
                symbolIds.putIfAbsent(simple, symbolId);
            }
            String hash = sha256(chunk.content());
            chunks.add(new IndexedChunk(chunk.startLine(), chunk.endLine(), chunk.chunkType(), chunk.name(), symbolId, chunk.content(), normalizer.normalize(chunk.name() + "\n" + chunk.content()), hash));
        }
        List<IndexedRelation> relations = new ArrayList<>();
        if ("java".equals(file.language())) {
            for (CodeRelation relation : analyzer.analyzeContent(file.absolutePath(), snapshot.content())) {
                String from = findSymbol(symbolIds, relation.fromName(), fileSymbolId);
                String to = findTarget(symbolIds, relation.toName());
                relations.add(new IndexedRelation(from, to, relation.toName(), relation.relationType(), 1));
            }
        }
        return new FileIndexBatch(root, file.relativePath(), file.contentHash(), file.sizeBytes(), file.modifiedMillis(), file.language(), "INDEXED", null, chunks, symbols, relations);
    }

    private FileEmbeddingBatch embed(Path root, Path relative, List<IndexedChunk> chunks, EmbeddingProvider provider) throws Exception {
        List<String> inputs = new ArrayList<>();
        List<Integer> partCounts = new ArrayList<>();
        for (IndexedChunk chunk : chunks) {
            List<String> parts = inputPolicy.prepareDocumentParts(chunk.content());
            inputs.addAll(parts);
            partCounts.add(parts.size());
        }
        List<float[]> vectors = provider.embedAll(inputs);
        if (vectors.size() != inputs.size())
            throw new IllegalStateException("Embedding count mismatch");
        List<ChunkEmbedding> embeddings = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < chunks.size(); i++) {
            int count = partCounts.get(i);
            float[] combined = average(vectors.subList(offset, offset + count), provider.space().dimension());
            offset += count;
            IndexedChunk chunk = chunks.get(i);
            embeddings.add(new ChunkEmbedding(chunk.startLine(), chunk.endLine(), chunk.symbolId(), chunk.contentHash(), combined));
        }
        return new FileEmbeddingBatch(root, relative, provider.space(), embeddings);
    }

    private static float[] average(List<float[]> vectors, int dimension) {
        float[] result = new float[dimension];
        for (float[] vector : vectors) {
            if (vector.length != dimension)
                throw new IllegalArgumentException("Embedding dimension mismatch");
            for (int i = 0; i < dimension; i++) result[i] += vector[i];
        }
        double norm = 0;
        for (int i = 0; i < dimension; i++) {
            result[i] /= vectors.size();
            norm += result[i] * result[i];
        }
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < dimension; i++) result[i] *= scale;
        }
        return result;
    }

    private static String ownerId(List<IndexedSymbol> symbols, String qualified) {
        int dot = qualified.indexOf('.');
        String ownerName = dot > 0 ? qualified.substring(0, dot) : "";
        return symbols.stream().filter(symbol -> symbol.simpleName().equals(ownerName)).map(IndexedSymbol::symbolId).findFirst().orElse(null);
    }

    private static String findSymbol(Map<String, String> ids, String name, String fallback) {
        if (name == null)
            return fallback;
        String direct = ids.get(name);
        if (direct != null)
            return direct;
        return ids.entrySet().stream().filter(entry -> entry.getKey().startsWith(name + ".")).map(Map.Entry::getValue).findFirst().orElse(fallback);
    }

    private static String findTarget(Map<String, String> ids, String name) {
        if (name == null)
            return null;
        String direct = ids.get(name);
        if (direct != null)
            return direct;
        return ids.entrySet().stream().filter(entry -> simpleName(entry.getKey()).equals(name)).map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static String simpleName(String qualified) {
        String value = qualified == null ? "" : qualified;
        int dot = value.lastIndexOf('.');
        String simple = dot >= 0 ? value.substring(dot + 1) : value;
        int paren = simple.indexOf('(');
        if (paren > 0)
            simple = simple.substring(0, paren).trim();
        int space = simple.lastIndexOf(' ');
        return space >= 0 ? simple.substring(space + 1) : simple;
    }

    private static Path canonicalRoot(Path root) throws Exception {
        return root.toRealPath().normalize();
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
