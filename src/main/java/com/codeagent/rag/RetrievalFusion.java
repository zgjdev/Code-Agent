package com.codeagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RetrievalFusion {
    private static final int RRF_K = 60;
    private static final Map<RetrievalSource, Double> WEIGHTS = weights();

    public List<RetrievalHit> fuse(Map<RetrievalSource, List<RetrievalCandidate>> rankings,
                                   String query, int limit) {
        if (limit <= 0) return List.of();
        if (!rankings.getOrDefault(RetrievalSource.FTS_TERMS, List.of()).isEmpty()
                && (!rankings.getOrDefault(RetrievalSource.SEMANTIC_LOCAL, List.of()).isEmpty()
                || !rankings.getOrDefault(RetrievalSource.SEMANTIC_REMOTE, List.of()).isEmpty()))
            return interleave(rankings, query, limit);
        Map<String, Accumulator> candidates = new LinkedHashMap<>();
        for (RetrievalSource source : RetrievalSource.values()) {
            List<RetrievalCandidate> ranked = rankings.getOrDefault(source, List.of());
            for (int index = 0; index < ranked.size(); index++) {
                RetrievalCandidate candidate = ranked.get(index);
                String key = key(candidate);
                Accumulator accumulator = candidates.computeIfAbsent(key,
                        ignored -> new Accumulator(candidate));
                accumulator.sources.add(source);
                accumulator.score += WEIGHTS.getOrDefault(source, 1.0) / (RRF_K + index + 1.0);
            }
        }
        List<RetrievalHit> hits = new ArrayList<>();
        for (Accumulator value : candidates.values()) {
            RetrievalCandidate candidate = value.candidate;
            double factor = 1.0;
            if ("class".equals(candidate.chunkType()) || "method".equals(candidate.chunkType())) factor *= 1.05;
            if (value.sources.size() >= 2) factor *= 1.05;
            hits.add(new RetrievalHit(candidate.filePath(), candidate.startLine(), candidate.endLine(),
                    candidate.chunkType(), candidate.symbol(), candidate.content(), value.score * factor,
                    value.sources));
        }
        hits.sort(Comparator.comparingDouble(RetrievalHit::score).reversed()
                .thenComparing(RetrievalHit::filePath).thenComparingInt(RetrievalHit::startLine));
        Map<String, Integer> perFile = new LinkedHashMap<>();
        List<RetrievalHit> limited = new ArrayList<>();
        for (RetrievalHit hit : hits) {
            int count = perFile.getOrDefault(hit.filePath(), 0);
            if (count >= 3) continue;
            limited.add(hit);
            perFile.put(hit.filePath(), count + 1);
            if (limited.size() >= limit) break;
        }
        return List.copyOf(limited);
    }

    private List<RetrievalHit> interleave(Map<RetrievalSource, List<RetrievalCandidate>> rankings,
                                         String query, int limit) {
        var lexical = lane(rankings, List.of(RetrievalSource.FTS_TERMS));
        var semantic = lane(rankings, List.of(RetrievalSource.SEMANTIC_LOCAL, RetrievalSource.SEMANTIC_REMOTE));
        boolean identifier = isIdentifierQuery(query);
        List<RetrievalHit> primary = identifier ? lexical : semantic, secondary = identifier ? semantic : lexical;
        List<RetrievalHit> output = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
        Map<String, Integer> perFile = new LinkedHashMap<>(); int p = 0, s = 0;
        Map<String, Set<RetrievalSource>> sources = new LinkedHashMap<>();
        rankings.forEach((source, candidates) -> candidates.forEach(c -> sources.computeIfAbsent(
                c.filePath() + ':' + c.startLine() + ':' + c.endLine() + ':' + c.symbol(), ignored -> new LinkedHashSet<>()).add(source)));
        while (output.size() < limit && (p < primary.size() || s < secondary.size())) {
            boolean supplement = !identifier && output.size() % 4 == 3 && s < secondary.size();
            RetrievalHit hit = supplement || p >= primary.size() ? secondary.get(s++) : primary.get(p++);
            String key = hit.filePath() + ':' + hit.startLine() + ':' + hit.endLine() + ':' + hit.symbol();
            if (!seen.add(key) || perFile.getOrDefault(hit.filePath(), 0) >= 3) continue;
            output.add(new RetrievalHit(hit.filePath(), hit.startLine(), hit.endLine(), hit.chunkType(), hit.symbol(),
                    hit.content(), hit.score(), sources.getOrDefault(key, hit.sources())));
            perFile.merge(hit.filePath(), 1, Integer::sum);
        }
        return List.copyOf(output);
    }

    static boolean isIdentifierQuery(String query) {
        if (query == null || query.isBlank()) return false;
        String[] terms = query.trim().split("\\s+");
        boolean explicitCodeShape = false;
        for (String term : terms) {
            if (!term.matches("[A-Za-z_$][A-Za-z0-9_$.]*(?:\\(\\))?")) return false;
            explicitCodeShape |= term.matches(".*(?:[a-z][A-Z]|[A-Z]{2}|[._$]|\\(\\)).*");
        }
        return terms.length == 1 || explicitCodeShape;
    }

    /** Preserve native candidate order; diversity and deduplication apply once, after interleaving. */
    private static List<RetrievalHit> lane(Map<RetrievalSource, List<RetrievalCandidate>> rankings,
                                         List<RetrievalSource> sources) {
        List<RetrievalHit> hits = new ArrayList<>();
        for (var source : sources) {
            var candidates = rankings.getOrDefault(source, List.of());
            for (int i = 0; i < candidates.size(); i++) {
                var c = candidates.get(i);
                hits.add(new RetrievalHit(c.filePath(), c.startLine(), c.endLine(), c.chunkType(), c.symbol(),
                        c.content(), WEIGHTS.get(source) / (RRF_K + i + 1.0), Set.of(source)));
            }
        }
        return hits;
    }

    private static String key(RetrievalCandidate candidate) {
        return candidate.filePath().replace('\\', '/') + ':' + candidate.startLine() + ':'
                + candidate.endLine() + ':' + String.valueOf(candidate.symbolId());
    }

    private static Map<RetrievalSource, Double> weights() {
        Map<RetrievalSource, Double> values = new EnumMap<>(RetrievalSource.class);
        values.put(RetrievalSource.FTS_TERMS, 1.2);
        values.put(RetrievalSource.SEMANTIC_LOCAL, 1.0);
        values.put(RetrievalSource.SEMANTIC_REMOTE, 1.0);
        return Map.copyOf(values);
    }

    private static final class Accumulator {
        private final RetrievalCandidate candidate;
        private final Set<RetrievalSource> sources = new LinkedHashSet<>();
        private double score;
        private Accumulator(RetrievalCandidate candidate) { this.candidate = candidate; }
    }
}
