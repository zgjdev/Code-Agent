package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingResolution;
import com.codeagent.rag.embedding.InProcessBgeEmbeddingProvider;
import com.codeagent.rag.stage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in experiment: real production source, bundled model, no network or Agent calls. */
@EnabledIfSystemProperty(named = "rag.repository.eval", matches = "true")
class RepositoryRetrievalEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TOP_K = 10, MAX_CHARS = 16_000, REPEATS = 3;

    @Test void reportsRealRepositoryAblations() throws Exception {
        Path repository = Path.of("").toAbsolutePath().normalize();
        Path output = Files.createDirectories(repository.resolve("target/rag-evaluation"));
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(repository.resolve("src/main/java"))) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                    && p.toString().endsWith(".java")).sorted().toList()) {
                assertFalse(Files.isSymbolicLink(path));
                files.put(repository.relativize(path).toString().replace('\\', '/'),
                        hash(Files.readAllBytes(path)));
            }
        }
        assertTrue(files.size() > 100, "must evaluate full production corpus");
        String corpusHash = hash(JSON.writeValueAsBytes(files));
        Path corpus = Files.createDirectories(output.resolve("corpus-" + corpusHash));
        for (var entry : files.entrySet()) {
            Path destination = corpus.resolve(entry.getKey());
            Files.createDirectories(destination.getParent());
            Files.copy(repository.resolve(entry.getKey()), destination, StandardCopyOption.REPLACE_EXISTING);
            assertEquals(entry.getValue(), hash(Files.readAllBytes(destination)), "source changed during snapshot");
        }
        var cases = RepositoryEvaluationDatasetTest.load();
        // Validate before the expensive experiment even when invoked without the dataset unit test.
        new RepositoryEvaluationDatasetTest().validatesRealSourceEvidenceAndBalancedCategories();
        List<RepositoryEvaluationReport.Row> rows = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("startedAt", Instant.now().toString());
        metadata.put("status", "IN_PROGRESS");
        metadata.put("gitHead", gitHead(repository));
        metadata.put("corpusScope", "all src/main/java/**/*.java, excluding docs/tests/evaluation data");
        metadata.put("corpusSha256", corpusHash);
        metadata.put("files", files);
        metadata.put("datasetSha256", hash(Files.readAllBytes(repository.resolve(
                "src/test/resources/rag/repository-evaluation.json"))));
        metadata.put("topK", TOP_K);
        metadata.put("maxChars", MAX_CHARS);
        metadata.put("warmupsPerQueryPerMode", 1);
        metadata.put("measuredRepeatsPerQueryPerMode", REPEATS);
        metadata.put("javaVersion", System.getProperty("java.version"));
        metadata.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        metadata.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        var index = new SqliteRetrievalIndex(output.resolve("index-" + corpusHash + ".db"));
        var provider = new InProcessBgeEmbeddingProvider();
        try (var service = new DefaultCodeRetrievalService(index,
                new EmbeddingResolution(Optional.of(provider), "local", false))) {
            long started = System.nanoTime();
            System.out.println("RAG evaluation indexing " + files.size() + " production Java files");
            var refresh = service.refresh(new IndexRefreshRequest(corpus, false));
            metadata.put("indexRefreshMillis", (System.nanoTime() - started) / 1_000_000);
            metadata.put("refresh", refresh);
            metadata.put("indexStatus", service.status());
            metadata.put("embeddingSpace", provider.space());
            assertEquals(0, refresh.failedFiles());
            assertTrue(refresh.reasonCodes().isEmpty(), "degraded index: " + refresh.reasonCodes());
            assertEquals(files.size(), service.status().indexedFileCount());
            var modes = new LinkedHashMap<String, List<CodeRetrieverStage>>();
            modes.put("lexical", List.of(new TermFtsRetriever()));
            modes.put("semantic", List.of(new SemanticRetriever()));
            modes.put("lexical_semantic", List.of(new TermFtsRetriever(), new SemanticRetriever()));
            modes.put("lexical_graph", List.of(new TermFtsRetriever(), new GraphRetriever()));
            modes.put("full", List.of(new TermFtsRetriever(), new GraphRetriever(), new SemanticRetriever()));
            for (var mode : modes.entrySet()) {
                System.out.println("RAG evaluation mode=" + mode.getKey());
                for (var item : cases) {
                    var request = new RetrievalRequest(corpus, item.query(), TOP_K, MAX_CHARS,
                            false, RetrievalIntent.CHUNKS);
                    search(mode.getValue(), new RetrievalContext(request, index, Optional.of(provider)));
                    List<Long> timings = new ArrayList<>();
                    List<RetrievalHit> hits = List.of();
                    Map<RetrievalSource, Integer> stageCounts = Map.of();
                    int graphSeeds = (int) index.searchSymbols(corpus, item.query(), 20).stream()
                            .map(RetrievalCandidate::symbolId).filter(Objects::nonNull).distinct().count();
                    if (item.category().equals("relation_probe"))
                        assertTrue(graphSeeds > 0, "relation probe must activate a seed: " + item.id());
                    for (int repeat = 0; repeat < REPEATS; repeat++) {
                        long before = System.nanoTime();
                        var measured = search(mode.getValue(), new RetrievalContext(request, index,
                                Optional.of(provider)));
                        timings.add(System.nanoTime() - before);
                        if (repeat == 0) {
                            hits = measured.hits();
                            stageCounts = measured.stageCounts();
                        } else assertEquals(hits, measured.hits(), "non-deterministic ranking: " + item.id());
                    }
                    if (mode.getKey().equals("full")) {
                        var actual = service.search(request);
                        assertTrue(actual.diagnostics().degradedReasonCodes().isEmpty());
                        assertEquals(actual.hits(), hits, "ablation must match production pipeline");
                    }
                    if (item.category().equals("relation_probe") && stageCounts.containsKey(RetrievalSource.GRAPH))
                        assertTrue(stageCounts.get(RetrievalSource.GRAPH) > 0,
                                "relation probe must produce GRAPH candidates: " + item.id());
                    rows.add(new RepositoryEvaluationReport.Row(mode.getKey(), item.id(), item.category(),
                            item.query(), item.evidence(),
                            RetrievalEvaluationMetrics.measure(item.evidence(), hits, 5),
                            RetrievalEvaluationMetrics.measure(item.evidence(), hits, 10), timings,
                            graphSeeds, stageCounts,
                            hits.stream().map(h -> describe(h, item.evidence())).toList()));
                }
                // Write completed modes incrementally so later failures do not erase evidence.
                RepositoryEvaluationReport.write(output, metadata, rows);
            }
        }
        assertEquals(78 * 5, rows.size());
        metadata.put("status", "COMPLETE");
        metadata.put("finishedAt", Instant.now().toString());
        RepositoryEvaluationReport.write(output, metadata, rows);
        System.out.println(Files.readString(output.resolve("report.md")));
    }

    private record SearchResult(List<RetrievalHit> hits, Map<RetrievalSource, Integer> stageCounts) {}

    private static SearchResult search(List<CodeRetrieverStage> stages, RetrievalContext context) {
        var result = new RetrievalStageRunner().run(stages, context);
        assertTrue(result.degradedReasonCodes().isEmpty(), "degraded stage: " + result.degradedReasonCodes());
        var fused = new RetrievalFusion().fuse(result.rankings(), context.request().query(),
                Math.max(context.request().topK() * 3, 15));
        return new SearchResult(new RetrievalBudget().apply(fused,
                context.request().topK(), context.request().maxChars()).hits(), result.hits());
    }

    private static RepositoryEvaluationReport.Hit describe(RetrievalHit hit,
            List<RetrievalEvaluationMetrics.Evidence> evidence) {
        Map<String, Integer> offsets = new LinkedHashMap<>();
        for (var unit : evidence) {
            offsets.put(unit.marker(), hit.content().indexOf(unit.marker()));
            for (String body : unit.requiredText()) offsets.put(body, hit.content().indexOf(body));
        }
        try {
            return new RepositoryEvaluationReport.Hit(hit.filePath(), hit.startLine(), hit.endLine(),
                    hit.symbol(), hit.sources(), hit.content().length(),
                    hash(hit.content().getBytes(StandardCharsets.UTF_8)), offsets,
                    evidence.stream().filter(e -> e.matches(hit)).toList());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot fingerprint returned content", failure);
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String gitHead(Path root) throws Exception {
        var process = new ProcessBuilder("git", "rev-parse", "HEAD").directory(root.toFile()).start();
        String head = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor());
        return head;
    }
}
