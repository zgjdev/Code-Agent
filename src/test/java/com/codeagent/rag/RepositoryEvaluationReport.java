package com.codeagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class RepositoryEvaluationReport {
    record Hit(String path, int startLine, int endLine, String symbol, Set<RetrievalSource> sources,
               int contentChars, String contentSha256, Map<String, Integer> markerOffsets,
               List<RetrievalEvaluationMetrics.Evidence> matchedEvidence) {}
    record Row(String mode, String id, String category, String query,
               List<RetrievalEvaluationMetrics.Evidence> evidence,
               RetrievalEvaluationMetrics.Scores at5, RetrievalEvaluationMetrics.Scores at10,
               List<Long> latencyNanos, Map<RetrievalSource, Integer> stageCandidates,
               List<Hit> hits) {}

    static void write(Path output, Map<String, Object> metadata, List<Row> rows) throws Exception {
        metadata.put("rowCount", rows.size());
        metadata.put("completedModes", rows.stream().map(Row::mode).distinct().toList());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(),
                Map.of("metadata", metadata, "rows", rows));
        StringBuilder text = new StringBuilder("# Repository RAG evaluation\n\n")
                .append("Status: ").append(metadata.get("status")).append("; rows: ").append(rows.size()).append("\n\n")
                .append("Corpus: ").append(metadata.get("corpusSha256")).append("\n\n")
                .append("Production Java only; local BGE; Top10/maxChars=16000. P/Recall/Hit@5 use its prefix.\n")
                .append("Metrics refer to annotated evidence, not exhaustive relevance or answer correctness.\n")
                .append("P@5 counts relevant returned chunks / 5. Discounted evidence coverage is a custom metric, NOT nDCG.\n")
                .append("Latency: warm component pipeline, one warmup then three serial measurements; service overhead and indexing excluded.\n")
                .append("69-query main set; six callee lookup probes are separate. Three deleted Graph-target queries were retired.\n\n")
                .append("| Mode / category | N positive | P@5 | R@5 | R@10 | Hit@1 | Hit@5 | Hit@10 | MRR@10 | Discounted coverage@10 | Complete@10 | No-result rate | No-answer false return | P50 ms | P95 ms |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (String mode : rows.stream().map(Row::mode).distinct().toList()) {
            var group = rows.stream().filter(r -> r.mode().equals(mode)).toList();
            appendSummary(text, mode + " / main", group.stream()
                    .filter(r -> !r.category().equals("symbol_reference")).toList());
            for (String category : group.stream().map(Row::category).distinct().toList())
                appendSummary(text, mode + " / " + category,
                        group.stream().filter(r -> r.category().equals(category)).toList());
        }
        text.append("\n## Full-mode failures (no evidence in first 10)\n\n");
        for (Row row : rows) {
            if (row.mode().equals("full") && !row.evidence().isEmpty() && !row.at10().hit()) {
                text.append("- ").append(row.id()).append(": ").append(row.query())
                        .append(" Expected: ").append(row.evidence().stream().map(e -> e.path()).distinct().toList())
                        .append("; first hit: ").append(row.hits().isEmpty() ? "EMPTY" : row.hits().get(0).path())
                        .append("\n");
            }
        }
        Files.writeString(output.resolve("report.md"), text);
    }

    private static void appendSummary(StringBuilder text, String label, List<Row> rows) {
        var positives = rows.stream().filter(r -> !r.evidence().isEmpty()).toList();
        var absent = rows.stream().filter(r -> r.evidence().isEmpty()).toList();
        var timings = rows.stream().flatMap(r -> r.latencyNanos().stream()).sorted().toList();
        text.append("| ").append(label).append(" | ").append(positives.size()).append(" | ");
        if (positives.isEmpty()) text.append("— | — | — | — | — | — | — | — | — | ");
        else {
            double[] metrics = {
                    positives.stream().mapToDouble(r -> r.at5().precision()).average().orElseThrow(),
                    positives.stream().mapToDouble(r -> r.at5().recall()).average().orElseThrow(),
                    positives.stream().mapToDouble(r -> r.at10().recall()).average().orElseThrow(),
                    positives.stream().mapToDouble(r -> !r.hits().isEmpty()
                            && !r.hits().get(0).matchedEvidence().isEmpty() ? 1 : 0).average().orElseThrow(),
                    positives.stream().mapToDouble(r -> r.at5().hit() ? 1 : 0).average().orElseThrow(),
                    positives.stream().mapToDouble(r -> r.at10().hit() ? 1 : 0).average().orElseThrow(),
                    positives.stream().mapToDouble(r -> r.at10().reciprocalRank()).average().orElseThrow(),
                    positives.stream().mapToDouble(r -> r.at10().discountedEvidenceCoverage()).average().orElseThrow(),
                    positives.stream().mapToDouble(r -> r.at10().complete() ? 1 : 0).average().orElseThrow()};
            for (double metric : metrics) text.append(String.format(Locale.ROOT, "%.4f | ", metric));
        }
        text.append(String.format(Locale.ROOT, "%.4f | ",
                rows.stream().mapToDouble(r -> r.hits().isEmpty() ? 1 : 0).average().orElseThrow()));
        text.append(absent.isEmpty() ? "—" : String.format(Locale.ROOT, "%.4f",
                absent.stream().mapToDouble(r -> r.at10().falseReturn() ? 1 : 0).average().orElseThrow()));
        text.append(String.format(Locale.ROOT, " | %.2f | %.2f |\n", percentile(timings, .5), percentile(timings, .95)));
    }
    private static double percentile(List<Long> values, double p) {
        return values.get(Math.max(0, (int) Math.ceil(values.size() * p) - 1)) / 1_000_000.0;
    }
}
