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
            if (value.sources.contains(RetrievalSource.SYMBOL)
                    && candidate.symbol() != null && candidate.symbol().equals(query)) factor *= 1.15;
            if ("class".equals(candidate.chunkType()) || "method".equals(candidate.chunkType())) factor *= 1.05;
            if (value.sources.contains(RetrievalSource.GRAPH) && value.sources.size() > 1) factor *= 1.03;
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

    private static String key(RetrievalCandidate candidate) {
        return candidate.filePath().replace('\\', '/') + ':' + candidate.startLine() + ':'
                + candidate.endLine() + ':' + String.valueOf(candidate.symbolId());
    }

    private static Map<RetrievalSource, Double> weights() {
        Map<RetrievalSource, Double> values = new EnumMap<>(RetrievalSource.class);
        values.put(RetrievalSource.SYMBOL, 1.5);
        values.put(RetrievalSource.LIVE_GREP, 1.4);
        values.put(RetrievalSource.FTS_TERMS, 1.2);
        values.put(RetrievalSource.SEMANTIC_LOCAL, 1.0);
        values.put(RetrievalSource.SEMANTIC_REMOTE, 1.0);
        values.put(RetrievalSource.FTS_TRIGRAM, 0.8);
        values.put(RetrievalSource.GRAPH, 0.7);
        return Map.copyOf(values);
    }

    private static final class Accumulator {
        private final RetrievalCandidate candidate;
        private final Set<RetrievalSource> sources = new LinkedHashSet<>();
        private double score;
        private Accumulator(RetrievalCandidate candidate) { this.candidate = candidate; }
    }
}
