package com.codeagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Paired frozen-corpus comparison; no model inference or network. */
@EnabledIfSystemProperty(named = "rag.qwen.compare", matches = "true")
class Qwen3ComparisonReportTest {
    @Test void verifiesFairnessAndWritesPairedReport() throws Exception {
        var json = new ObjectMapper();
        Path root = Path.of("target/qwen-evaluation");
        Map<String, JsonNode> runs = new LinkedHashMap<>();
        for (String model : List.of("bge", "qwen1024", "qwen512", "qwen-fp32"))
            runs.put(model, json.readTree(root.resolve(model).resolve("results.json").toFile()));
        JsonNode baseline = runs.get("bge");
        Set<String> spaces = new HashSet<>();
        List<Map<String, Object>> summaries = new ArrayList<>(), pairs = new ArrayList<>();
        StringBuilder report = new StringBuilder("# Frozen-corpus Qwen comparison\n\n63 positive main queries; six absent queries and six caller probes reported separately. Annotated evidence recall, not answer accuracy.\n\n| Model | Mode/category | N positive | P@5 | R@5 | R@10 | MRR@10 | N absent | False returns | P50 ms | P95 ms |\n|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (var run : runs.entrySet()) {
            JsonNode metadata = run.getValue().path("metadata");
            assertEquals("COMPLETE", metadata.path("status").asText());
            assertEquals(225, metadata.path("rowCount").asInt());
            assertFalse(metadata.path("evaluationHarnessSha256").isMissingNode(), "record the tested harness");
            for (String field : List.of("corpusSha256", "datasetSha256", "files", "pipelineSourcesSha256", "evaluationHarnessSha256", "topK", "maxChars", "measuredRepeatsPerQueryPerMode"))
                assertEquals(baseline.path("metadata").path(field), metadata.path(field), field);
            String space = metadata.path("embeddingSpace").path("embeddingSpaceId").asText();
            assertFalse(space.isBlank());
            assertEquals(Set.of("bge", "qwen512").contains(run.getKey()) ? 512 : 1024,
                    metadata.path("embeddingSpace").path("dimension").asInt());
            assertTrue(spaces.add(space), "model spaces must be distinct");
            var indexed = index(run.getValue());
            var baseIndex = index(baseline);
            assertEquals(baseIndex.keySet(), indexed.keySet());
            for (var entry : indexed.entrySet()) {
                JsonNode base = baseIndex.get(entry.getKey()), row = entry.getValue();
                for (String field : List.of("category", "query", "evidence")) assertEquals(base.path(field), row.path(field));
                if (row.path("mode").asText().equals("lexical")) assertEquals(
                        normalizedHits(base, root.resolve("bge"), baseline.path("metadata").path("corpusSha256").asText()),
                        normalizedHits(row, root.resolve(run.getKey()), metadata.path("corpusSha256").asText()), "lexical control changed");
                if (!run.getKey().equals("bge") && !row.path("category").asText().equals("symbol_reference") && !row.path("evidence").isEmpty()) {
                    Map<String, Object> pair = new LinkedHashMap<>();
                    pair.put("model", run.getKey()); pair.put("id", row.path("id").asText());
                    pair.put("mode", row.path("mode").asText()); pair.put("category", row.path("category").asText());
                    pair.put("query", row.path("query").asText());
                    pair.put("baselineRecall10", base.path("at10").path("recall").asDouble());
                    pair.put("recall10", row.path("at10").path("recall").asDouble());
                    pair.put("deltaRecall10", row.path("at10").path("recall").asDouble() - base.path("at10").path("recall").asDouble());
                    pair.put("deltaMRR10", row.path("at10").path("reciprocalRank").asDouble() - base.path("at10").path("reciprocalRank").asDouble());
                    pairs.add(pair);
                }
            }
            for (String mode : List.of("lexical", "semantic", "full")) {
                Set<String> categories = new LinkedHashSet<>(List.of("main"));
                indexed.values().forEach(r -> categories.add(r.path("category").asText()));
                for (String category : categories) {
                    var group = indexed.values().stream().filter(r -> r.path("mode").asText().equals(mode))
                            .filter(r -> category.equals("main") ? !r.path("category").asText().equals("symbol_reference") : r.path("category").asText().equals(category)).toList();
                    var positive = group.stream().filter(r -> !r.path("evidence").isEmpty()).toList();
                    if (category.equals("main")) { assertEquals(69, group.size()); assertEquals(63, positive.size()); }
                    List<Long> times = new ArrayList<>();
                    group.forEach(r -> r.path("latencyNanos").forEach(t -> times.add(t.asLong()))); times.sort(Long::compare);
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("model", run.getKey()); summary.put("mode", mode); summary.put("category", category); summary.put("positiveCount", positive.size());
                    summary.put("precision5", mean(positive, "at5", "precision")); summary.put("recall5", mean(positive, "at5", "recall"));
                    summary.put("recall10", mean(positive, "at10", "recall")); summary.put("mrr10", mean(positive, "at10", "reciprocalRank"));
                    summary.put("absentCount", group.size() - positive.size());
                    summary.put("absentFalseReturns", group.stream().filter(r -> r.path("evidence").isEmpty() && r.path("at10").path("falseReturn").asBoolean()).count());
                    summary.put("p50Millis", percentile(times, .5)); summary.put("p95Millis", percentile(times, .95)); summaries.add(summary);
                    report.append(String.format(Locale.ROOT, "| %s | %s/%s | %d | ", run.getKey(), mode, category, positive.size()));
                    if (positive.isEmpty()) report.append("— | — | — | — | ");
                    else report.append(String.format(Locale.ROOT, "%.4f | %.4f | %.4f | %.4f | ", summary.get("precision5"), summary.get("recall5"), summary.get("recall10"), summary.get("mrr10")));
                    report.append(String.format(Locale.ROOT, "%d | %d | %.2f | %.2f |%n", summary.get("absentCount"), summary.get("absentFalseReturns"), summary.get("p50Millis"), summary.get("p95Millis")));
                }
            }
        }
        json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("comparison.json").toFile(), Map.of("summaries", summaries, "pairedRows", pairs,
                "comparisonSourceSha256", Qwen3EvaluationProvider.hash(Files.readAllBytes(Path.of("src/test/java/com/codeagent/rag/Qwen3ComparisonReportTest.java")))));
        Files.writeString(root.resolve("comparison.md"), report);
    }
    private static JsonNode normalizedHits(JsonNode row, Path output, String corpusHash) {
        JsonNode hits = row.path("hits").deepCopy();
        String prefix = output.toAbsolutePath().normalize().resolve("corpus-" + corpusHash).toString();
        hits.forEach(hit -> {
            String symbol = hit.path("symbol").asText();
            if (symbol.startsWith(prefix + java.io.File.separator))
                ((com.fasterxml.jackson.databind.node.ObjectNode) hit).put("symbol", "<CORPUS>" + symbol.substring(prefix.length()));
        });
        return hits;
    }
    private static Map<String, JsonNode> index(JsonNode run) {
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        run.path("rows").forEach(r -> assertNull(rows.put(r.path("mode").asText() + ":" + r.path("id").asText(), r), "duplicate row"));
        assertEquals(225, rows.size()); return rows;
    }
    private static Double mean(List<JsonNode> rows, String at, String metric) {
        return rows.isEmpty() ? null : rows.stream().mapToDouble(r -> r.path(at).path(metric).asDouble()).average().orElseThrow();
    }
    private static double percentile(List<Long> times, double percentile) {
        return times.get((int) Math.ceil(times.size() * percentile) - 1) / 1_000_000.0;
    }
}
