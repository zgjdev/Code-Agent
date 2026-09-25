package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingInputPolicy;
import com.codeagent.rag.embedding.EmbeddingProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Coordinates lexical file transactions and independent embedding backfill transactions. */
public final class IndexCoordinator {
    private final SqliteRetrievalIndex index;
    private final Optional<EmbeddingProvider> embeddingProvider;
    private final IndexFileScanner scanner;
    private final CodeChunker chunker;
    private final CodeAnalyzer analyzer;
    private final LexicalTextNormalizer normalizer;
    private final EmbeddingInputPolicy inputPolicy;

    public IndexCoordinator(SqliteRetrievalIndex index, Optional<EmbeddingProvider> embeddingProvider) {
        this(index, embeddingProvider, new IndexFileScanner(), new CodeChunker(), new CodeAnalyzer(),
                new LexicalTextNormalizer(), new EmbeddingInputPolicy());
    }

    IndexCoordinator(SqliteRetrievalIndex index, Optional<EmbeddingProvider> embeddingProvider,
            IndexFileScanner scanner, CodeChunker chunker, CodeAnalyzer analyzer,
            LexicalTextNormalizer normalizer, EmbeddingInputPolicy inputPolicy) {
        this.index = index;
        this.embeddingProvider = embeddingProvider == null ? Optional.empty() : embeddingProvider;
        this.scanner = scanner;
        this.chunker = chunker;
        this.analyzer = analyzer;
        this.normalizer = normalizer;
        this.inputPolicy = inputPolicy;
    }

    public IndexRefreshResult refresh(IndexRefreshRequest request) {
        return execute(request, request.rebuild());
    }

    public IndexRefreshResult rebuild(IndexRefreshRequest request) {
        return execute(new IndexRefreshRequest(request.projectRoot(), true), true);
    }

    public void clear(Path projectRoot) {
        try {
            index.clearProject(canonicalRoot(projectRoot));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to clear retrieval index", e);
        }
    }

    private IndexRefreshResult execute(IndexRefreshRequest request, boolean rebuild) {
        int changed = 0;
        int unchanged = 0;
        int deleted = 0;
        int failed = 0;
        Set<String> reasons = new LinkedHashSet<>();
        IndexFileScanner.ScanResult scan = scanner.scan(request.projectRoot());
        if (!scan.complete()) reasons.add("scan_incomplete");
        if (scan.files().isEmpty() && !scan.complete()) {
            return new IndexRefreshResult(0, 0, 0, scan.failures().size(), List.copyOf(reasons));
        }
        try {
            Path root = canonicalRoot(request.projectRoot());
            if (rebuild && scan.complete()) index.clearProject(root);
            Map<String, FileSnapshot> previous = new HashMap<>();
            for (FileSnapshot snapshot : index.listFiles(root)) previous.put(snapshot.filePath(), snapshot);
            Set<String> seen = new HashSet<>();

            for (IndexFileScanner.ScannedFile file : scan.files()) {
                String key = normalize(file.relativePath());
                seen.add(key);
                FileSnapshot old = previous.get(key);
                boolean lexicalChanged = old == null || !old.contentHash().equals(file.contentHash());
                List<IndexedChunk> chunks;
                if (lexicalChanged) {
                    try {
                        FileIndexBatch batch = buildLexicalBatch(root, file);
                        index.replaceLexicalFile(batch);
                        chunks = batch.chunks();
                        changed++;
                    } catch (Exception e) {
                        failed++;
                        reasons.add("lexical_index_failed");
                        continue;
                    }
                } else {
                    unchanged++;
                    chunks = index.listChunks(root, file.relativePath());
                }
                if (embeddingProvider.isPresent()) {
                    EmbeddingProvider provider = embeddingProvider.orElseThrow();
                    if (!index.hasCompleteEmbeddings(root, file.relativePath(),
                            provider.space().embeddingSpaceId(), chunks.size())) {
                        try {
                            index.replaceFileEmbeddings(embed(root, file.relativePath(), chunks, provider));
                        } catch (Exception e) {
                            reasons.add("embedding_failed");
                        }
                    }
                }
            }

            if (scan.complete()) {
                for (FileSnapshot old : previous.values()) {
                    if (!seen.contains(old.filePath())) {
                        index.deleteFile(root, Path.of(old.filePath()));
                        deleted++;
                    }
                }
            }
        } catch (Exception e) {
            failed++;
            reasons.add("index_refresh_failed");
        }
        failed += scan.failures().size();
        return new IndexRefreshResult(changed, unchanged, deleted, failed, List.copyOf(reasons));
    }

    private FileIndexBatch buildLexicalBatch(Path root, IndexFileScanner.ScannedFile file) throws Exception {
        List<CodeChunk> rawChunks = chunker.chunkFile(file.absolutePath());
        String relative = normalize(file.relativePath());
        String fileSymbolId = StableSymbolId.create(relative, "FILE", relative, "");
        List<IndexedSymbol> symbols = new ArrayList<>();
        symbols.add(new IndexedSymbol(fileSymbolId, relative, file.relativePath().getFileName().toString(),
                "", "FILE", null, 1, Math.max(1, Files.readString(file.absolutePath()).split("\\R", -1).length)));
        Map<String, String> symbolIds = new HashMap<>();
        symbolIds.put("file", fileSymbolId);
        List<IndexedChunk> chunks = new ArrayList<>();
        for (CodeChunk chunk : rawChunks) {
            String kind = chunk.chunkType().toUpperCase();
            String qualified = chunk.name();
            String signature = "METHOD".equals(kind) ? chunk.name() : "";
            String symbolId = "FILE".equals(kind) ? fileSymbolId
                    : StableSymbolId.create(relative, kind, qualified, signature);
            if (!"FILE".equals(kind)) {
                String simple = simpleName(qualified);
                String owner = "METHOD".equals(kind) ? ownerId(symbols, qualified) : fileSymbolId;
                symbols.add(new IndexedSymbol(symbolId, qualified, simple, signature, kind, owner,
                        chunk.startLine(), chunk.endLine()));
                symbolIds.put(qualified, symbolId);
                symbolIds.putIfAbsent(simple, symbolId);
            }
            String hash = sha256(chunk.content());
            chunks.add(new IndexedChunk(chunk.startLine(), chunk.endLine(), chunk.chunkType(),
                    chunk.name(), symbolId, chunk.content(),
                    normalizer.normalize(chunk.name() + "\n" + chunk.content()), hash));
        }
        List<IndexedRelation> relations = new ArrayList<>();
        if ("java".equals(file.language())) {
            for (CodeRelation relation : analyzer.analyzeFile(file.absolutePath())) {
                String from = findSymbol(symbolIds, relation.fromName(), fileSymbolId);
                String to = findTarget(symbolIds, relation.toName());
                relations.add(new IndexedRelation(from, to, relation.toName(), relation.relationType(), 1));
            }
        }
        return new FileIndexBatch(root, file.relativePath(), file.contentHash(), file.sizeBytes(),
                file.modifiedMillis(), file.language(), "INDEXED", null, chunks, symbols, relations);
    }

    private FileEmbeddingBatch embed(Path root, Path relative, List<IndexedChunk> chunks,
            EmbeddingProvider provider) throws Exception {
        List<String> inputs = new ArrayList<>();
        List<Integer> partCounts = new ArrayList<>();
        for (IndexedChunk chunk : chunks) {
            List<String> parts = inputPolicy.prepareDocumentParts(chunk.content());
            inputs.addAll(parts);
            partCounts.add(parts.size());
        }
        List<float[]> vectors = provider.embedAll(inputs);
        if (vectors.size() != inputs.size()) throw new IllegalStateException("Embedding count mismatch");
        List<ChunkEmbedding> embeddings = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < chunks.size(); i++) {
            int count = partCounts.get(i);
            float[] combined = average(vectors.subList(offset, offset + count), provider.space().dimension());
            offset += count;
            IndexedChunk chunk = chunks.get(i);
            embeddings.add(new ChunkEmbedding(chunk.startLine(), chunk.endLine(), chunk.symbolId(),
                    chunk.contentHash(), combined));
        }
        return new FileEmbeddingBatch(root, relative, provider.space(), embeddings);
    }

    private static float[] average(List<float[]> vectors, int dimension) {
        float[] result = new float[dimension];
        for (float[] vector : vectors) {
            if (vector.length != dimension) throw new IllegalArgumentException("Embedding dimension mismatch");
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
        return symbols.stream().filter(symbol -> symbol.simpleName().equals(ownerName))
                .map(IndexedSymbol::symbolId).findFirst().orElse(null);
    }

    private static String findSymbol(Map<String, String> ids, String name, String fallback) {
        if (name == null) return fallback;
        String direct = ids.get(name);
        if (direct != null) return direct;
        return ids.entrySet().stream().filter(entry -> entry.getKey().startsWith(name + "."))
                .map(Map.Entry::getValue).findFirst().orElse(fallback);
    }

    private static String findTarget(Map<String, String> ids, String name) {
        if (name == null) return null;
        String direct = ids.get(name);
        if (direct != null) return direct;
        return ids.entrySet().stream().filter(entry -> simpleName(entry.getKey()).equals(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static String simpleName(String qualified) {
        String value = qualified == null ? "" : qualified;
        int dot = value.lastIndexOf('.');
        String simple = dot >= 0 ? value.substring(dot + 1) : value;
        int paren = simple.indexOf('(');
        if (paren > 0) simple = simple.substring(0, paren).trim();
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
