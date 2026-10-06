package com.codeagent.rag;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Test-only metrics against explicitly annotated evidence, never metadata-only matches. */
final class RetrievalEvaluationMetrics {
    record Evidence(String path, String marker, List<String> requiredText) {
        Evidence {
            requiredText = requiredText == null ? List.of() : List.copyOf(requiredText);
        }
        Evidence(String path, String marker) { this(path, marker, List.of()); }
        boolean matches(RetrievalHit hit) {
            return path.equals(hit.filePath().replace('\\', '/')) && hit.content().contains(marker)
                    && requiredText.stream().allMatch(hit.content()::contains);
        }
    }
    record Scores(double precision, double recall, double reciprocalRank, double discountedEvidenceCoverage,
                  boolean hit, boolean complete, boolean falseReturn) {}

    static Scores measure(List<Evidence> evidence, List<RetrievalHit> hits, int k) {
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        Set<Evidence> recovered = new HashSet<>();
        int relevantChunks = 0;
        double rr = 0, discounted = 0;
        for (int rank = 0; rank < Math.min(k, hits.size()); rank++) {
            boolean relevant = false;
            int gain = 0;
            for (Evidence unit : evidence) {
                if (unit.matches(hits.get(rank))) {
                    relevant = true;
                    if (recovered.add(unit)) gain++;
                }
            }
            if (relevant) {
                relevantChunks++;
                if (rr == 0) rr = 1.0 / (rank + 1);
            }
            discounted += gain / log2(rank + 2);
        }
        return new Scores(relevantChunks / (double) k,
                evidence.isEmpty() ? 0 : recovered.size() / (double) evidence.size(),
                rr, evidence.isEmpty() ? 0 : discounted / evidence.size(), !recovered.isEmpty(),
                !evidence.isEmpty() && recovered.size() == evidence.size(),
                evidence.isEmpty() && !hits.isEmpty());
    }
    private static double log2(double n) { return Math.log(n) / Math.log(2); }
}
