package com.codeagent.rag;

import com.codeagent.rag.embedding.*;
import com.codeagent.rag.stage.TermFtsRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Evidence coverage before rank/diversity/budget, using the already frozen production index. */
@EnabledIfSystemProperty(named = "rag.candidate.eval", matches = "true")
class RepositoryCandidateRecallTest {
    @Test void reportsWhereEvidenceDisappears() throws Exception {
        var json = new ObjectMapper();
        Path baselineDirectory = Path.of("target/qwen-migration/production").toAbsolutePath();
        var baseline = json.readTree(baselineDirectory.resolve("results.json").toFile());
        assertEquals("COMPLETE", baseline.path("metadata").path("status").asText());
        String hash = baseline.path("metadata").path("corpusSha256").asText();
        Path corpus = baselineDirectory.resolve("corpus-" + hash);
        Path output = Files.createDirectories(Path.of("target/qwen-recall-optimization"));
        RepositoryEvaluationDatasetTest.validate(corpus);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean holdout = Boolean.getBoolean("rag.candidate.holdout");
        var cases = holdout ? RepositoryEvaluationDatasetTest.load("/rag/repository-recall-holdout.json") : RepositoryEvaluationDatasetTest.load();
        try (var provider = new InProcessQwen3EmbeddingProvider(InProcessQwen3EmbeddingProvider.defaultModelDirectory());
             var index = new SqliteRetrievalIndex(baselineDirectory.resolve("index-" + hash + ".db"));
             var service = new DefaultCodeRetrievalService(index, new EmbeddingResolution(Optional.of(provider), "local", false))) {
            assertEquals(3226, index.status(corpus).chunkCount());
            var policy = new EmbeddingInputPolicy();
            for (var item : cases) {
                var query = provider.embedAll(List.of(policy.prepareQuery(item.query()))).get(0);
                var semantic = index.searchVector(corpus, provider.space().embeddingSpaceId(), query, 100);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.id()); row.put("category", item.category()); row.put("query", item.query());
                row.put("evidence", item.evidence());
                for (int depth : List.of(30, 50, 100)) {
                    var lexical = new TermFtsRetriever().retrieve(new RetrievalContext(
                            new RetrievalRequest(corpus, item.query(), (depth + 3) / 4, 16000, false, RetrievalIntent.CHUNKS), index, Optional.of(provider)));
                    lexical = lexical.subList(0, Math.min(depth, lexical.size()));
                    row.put("lexical" + depth, measure(item, hits(lexical)));
                    row.put("semantic" + depth, measure(item, hits(semantic.subList(0, Math.min(depth, semantic.size())))));
                    var union = new ArrayList<>(hits(lexical)); union.addAll(hits(semantic.subList(0, Math.min(depth, semantic.size()))));
                    row.put("union" + depth, measure(item, union));
                }
                var request = new RetrievalRequest(corpus, item.query(), 10, 16000, false, RetrievalIntent.CHUNKS);
                var lexical = new TermFtsRetriever().retrieve(new RetrievalContext(request, index, Optional.of(provider)));
                row.put("semanticCandidates", semantic);
                row.put("lexicalCandidates", lexical);
                var currentSemantic = semantic.subList(0, Math.min(30, semantic.size()));
                var union = new ArrayList<>(hits(lexical)); union.addAll(hits(currentSemantic));
                row.put("currentCandidates", measure(item, union));
                var fused = new RetrievalFusion().fuse(Map.of(RetrievalSource.FTS_TERMS, lexical,
                        RetrievalSource.SEMANTIC_LOCAL, currentSemantic), item.query(), 30);
                row.put("fused30", measure(item, fused));
                var budgeted = new RetrievalBudget().apply(fused, 10, 16000).hits();
                row.put("final10", measure(item, budgeted));
                row.put("candidateEvidenceRanks", item.evidence().stream().map(e -> Map.of(
                        "evidence", e, "semanticRank", firstRank(e, hits(semantic)),
                        "lexicalRank", firstRank(e, hits(lexical)), "fusedRank", firstRank(e, fused),
                        "finalRank", firstRank(e, budgeted))).toList());
                var actual = service.search(request);
                var pipeline = new RetrievalPipeline().apply(Map.of(RetrievalSource.FTS_TERMS,lexical,
                        RetrievalSource.SEMANTIC_LOCAL,semantic),request);
                assertEquals(budgeted,pipeline.primaryHits());
                assertEquals(actual.hits(),pipeline.hits());
                row.put("context10",measure(item,pipeline.hits()));
                assertTrue(actual.diagnostics().degradedReasonCodes().isEmpty());
                rows.add(row);
            }
        }
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve(holdout ? "holdout-candidates.json" : "candidate-report.json").toFile(), Map.of(
                "baselineMetadata", baseline.path("metadata"), "traceSourceSha256", Qwen3EvaluationProvider.hash(Files.readAllBytes(Path.of("src/test/java/com/codeagent/rag/RepositoryCandidateRecallTest.java"))),
                "note", "One real query embedding per trace plus independent service verification. final10 denotes legacy primary chunks; context10 denotes current assembled results. Coverage is not budgeted recall or a latency benchmark.", "rows", rows));
        assertEquals(holdout ? 12 : 75, rows.size());
        for (String field : List.of("semantic30", "semantic50", "semantic100", "union30", "union50", "union100", "currentCandidates", "fused30", "final10")) {
            var main = rows.stream().filter(r -> !r.get("category").equals("symbol_reference") && !((List<?>) r.get("evidence")).isEmpty()).toList();
            System.out.println(field + " main coverage=" + main.stream().mapToDouble(r -> ((RetrievalEvaluationMetrics.Scores) r.get(field)).recall()).average().orElseThrow());
        }
    }
    private static RetrievalEvaluationMetrics.Scores measure(RepositoryEvaluationDatasetTest.QueryCase item, List<RetrievalHit> hits) {
        return RetrievalEvaluationMetrics.measure(item.evidence(), hits, Math.max(1, hits.size()));
    }
    private static List<RetrievalHit> hits(List<RetrievalCandidate> candidates) {
        return candidates.stream().map(c -> new RetrievalHit(c.filePath(), c.startLine(), c.endLine(), c.chunkType(), c.symbol(), c.content(), c.score(), Set.of())).toList();
    }
    private static int firstRank(RetrievalEvaluationMetrics.Evidence evidence, List<RetrievalHit> hits) {
        for (int i = 0; i < hits.size(); i++) if (evidence.matches(hits.get(i))) return i + 1;
        return -1;
    }
}
