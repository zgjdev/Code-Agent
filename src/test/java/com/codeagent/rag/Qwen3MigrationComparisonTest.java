package com.codeagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compare changed production pipeline with preserved model-only baselines on identical evidence. */
@EnabledIfSystemProperty(named = "rag.qwen.migration.compare", matches = "true")
class Qwen3MigrationComparisonTest {
    @Test void verifiesFrozenInputsAndReportsEveryPairedChange() throws Exception {
        var json = new ObjectMapper();
        Path output = Path.of("target/qwen-migration");
        var production = json.readTree(output.resolve("production/results.json").toFile());
        var runs = new LinkedHashMap<String, JsonNode>();
        runs.put("bge-before", json.readTree(Path.of("target/qwen-evaluation/bge/results.json").toFile()));
        runs.put("qwen-fp32-before", json.readTree(Path.of("target/qwen-evaluation/qwen-fp32/results.json").toFile()));
        runs.put("qwen-production", production);
        List<Map<String, Object>> summaries = new ArrayList<>(), pairs = new ArrayList<>();
        var current = index(production);
        StringBuilder report = new StringBuilder("# Production Qwen migration comparison\n\nFrozen corpus and annotations; changed pipeline hashes are expected. Evidence recall, not answer accuracy.\n\n| Run | Mode/category | N positive | P@5 | R@5 | R@10 | MRR@10 | Absent false returns | P50 ms | P95 ms |\n|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (var run : runs.entrySet()) {
            var metadata = run.getValue().path("metadata");
            assertEquals("COMPLETE", metadata.path("status").asText());
            for (String field : List.of("corpusSha256", "datasetSha256", "files", "topK", "maxChars", "measuredRepeatsPerQueryPerMode"))
                assertEquals(production.path("metadata").path(field), metadata.path(field), field);
            var rows = index(run.getValue());
            assertEquals(current.keySet(), rows.keySet());
            for (var entry : rows.entrySet()) {
                var actual = current.get(entry.getKey()); var before = entry.getValue();
                for (String field : List.of("category", "query", "evidence")) assertEquals(before.path(field), actual.path(field), field);
                if (!run.getKey().equals("qwen-production")) {
                    var pair = new LinkedHashMap<String, Object>();
                    pair.put("baseline", run.getKey()); pair.put("id", actual.path("id").asText());
                    pair.put("mode", actual.path("mode").asText()); pair.put("category", actual.path("category").asText());
                    pair.put("query", actual.path("query").asText());
                    pair.put("beforeRecall10", before.path("at10").path("recall").asDouble());
                    pair.put("afterRecall10", actual.path("at10").path("recall").asDouble());
                    pair.put("deltaRecall10", actual.path("at10").path("recall").asDouble() - before.path("at10").path("recall").asDouble());
                    pair.put("deltaMRR10", actual.path("at10").path("reciprocalRank").asDouble() - before.path("at10").path("reciprocalRank").asDouble());
                    pairs.add(pair);
                }
            }
            for (String mode : List.of("lexical", "semantic", "full")) {
                Set<String> categories = new LinkedHashSet<>(List.of("main"));
                rows.values().forEach(r -> categories.add(r.path("category").asText()));
                for (String category : categories) {
                    var group = rows.values().stream().filter(r -> r.path("mode").asText().equals(mode))
                            .filter(r -> category.equals("main") ? !r.path("category").asText().equals("symbol_reference") : r.path("category").asText().equals(category)).toList();
                    var positive = group.stream().filter(r -> !r.path("evidence").isEmpty()).toList();
                    if (category.equals("main")) { assertEquals(69, group.size()); assertEquals(63, positive.size()); }
                    List<Long> times = new ArrayList<>(); group.forEach(r -> r.path("latencyNanos").forEach(t -> times.add(t.asLong()))); times.sort(Long::compare);
                    var s = new LinkedHashMap<String, Object>();
                    s.put("run", run.getKey()); s.put("mode", mode); s.put("category", category); s.put("positiveCount", positive.size());
                    s.put("precision5", mean(positive, "at5", "precision")); s.put("recall5", mean(positive, "at5", "recall"));
                    s.put("recall10", mean(positive, "at10", "recall")); s.put("mrr10", mean(positive, "at10", "reciprocalRank"));
                    s.put("absentCount", group.size() - positive.size());
                    s.put("absentFalseReturns", group.stream().filter(r -> r.path("evidence").isEmpty() && r.path("at10").path("falseReturn").asBoolean()).count());
                    s.put("p50Millis", percentile(times, .5)); s.put("p95Millis", percentile(times, .95)); summaries.add(s);
                    report.append(String.format(Locale.ROOT, "| %s | %s/%s | %d | ", run.getKey(), mode, category, positive.size()));
                    if (positive.isEmpty()) report.append("— | — | — | — | ");
                    else report.append(String.format(Locale.ROOT, "%.4f | %.4f | %.4f | %.4f | ", s.get("precision5"), s.get("recall5"), s.get("recall10"), s.get("mrr10")));
                    report.append(String.format(Locale.ROOT, "%d | %.2f | %.2f |%n", s.get("absentFalseReturns"), s.get("p50Millis"), s.get("p95Millis")));
                }
            }
        }
        report.append("\n## All full-mode regressions against previous FP32\n\n");
        for (var pair : pairs) if (pair.get("baseline").equals("qwen-fp32-before") && pair.get("mode").equals("full")
                && ((double) pair.get("deltaRecall10") < 0 || (double) pair.get("deltaMRR10") < 0))
            report.append("- ").append(pair).append('\n');
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("comparison.json").toFile(), Map.of("summaries", summaries, "pairedRows", pairs,
                "metadata", runs.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().path("metadata")))));
        Files.writeString(output.resolve("comparison.md"), report);
        var main = current.values().stream().filter(r -> r.path("mode").asText().equals("full") && !r.path("category").asText().equals("symbol_reference") && !r.path("evidence").isEmpty()).toList();
        assertTrue(mean(main, "at10", "recall") >= .60, "predeclared main Recall@10 target");
        var identifiers = main.stream().filter(r -> r.path("category").asText().equals("identifier")).toList();
        assertEquals(19, identifiers.size());
        assertEquals(1.0, mean(identifiers, "at10", "recall"), "preserve explicit identifier evidence");
    }
    private static Map<String, JsonNode> index(JsonNode run) {
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        run.path("rows").forEach(r -> assertNull(rows.put(r.path("mode").asText() + ':' + r.path("id").asText(), r)));
        assertEquals(225, rows.size()); return rows;
    }
    private static Double mean(List<JsonNode> rows, String at, String metric) {
        return rows.isEmpty() ? null : rows.stream().mapToDouble(r -> r.path(at).path(metric).asDouble()).average().orElseThrow();
    }
    private static double percentile(List<Long> times, double p) { return times.get((int) Math.ceil(times.size() * p) - 1) / 1_000_000.0; }
}
