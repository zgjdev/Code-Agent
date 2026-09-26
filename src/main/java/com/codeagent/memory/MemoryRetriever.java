package com.codeagent.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 长期记忆检索器：词法 + 本地语义混合召回，并使用有下限的确认时间衰减稳定排序。
 */
public class MemoryRetriever implements AutoCloseable {
    static final double LEXICAL_WEIGHT = 0.45d;
    static final double SEMANTIC_WEIGHT = 0.55d;
    static final double SEMANTIC_MIN_SCORE = 0.475d;
    static final double WRITE_CANDIDATE_MIN_SCORE = 0.45d;
    static final double DECAY_FLOOR = 0.60d;
    static final double DECAY_HALF_LIFE_DAYS = 30.0d;

    private final LongTermMemory longTermMemory;
    private final MemoryEmbeddingCache embeddingCache;
    private final Clock clock;

    public MemoryRetriever(LongTermMemory longTermMemory) {
        this(longTermMemory, new MemoryEmbeddingCache(), Clock.systemUTC());
    }

    MemoryRetriever(LongTermMemory longTermMemory,
                    MemoryEmbeddingCache embeddingCache,
                    Clock clock) {
        this.longTermMemory = java.util.Objects.requireNonNull(longTermMemory, "longTermMemory");
        this.embeddingCache = java.util.Objects.requireNonNull(embeddingCache, "embeddingCache");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public List<MemoryEntry> retrieve(String query, int limit) {
        return retrieveLongTerm(query, limit, null);
    }

    public List<MemoryEntry> retrieveLongTerm(String query, int limit) {
        return retrieveLongTerm(query, limit, null);
    }

    public List<MemoryEntry> retrieveLongTerm(String query, int limit, String projectKey) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        return score(query, longTermMemory.getActiveVisible(projectKey), false).stream()
                .filter(scored -> scored.lexicalScore() > 0
                        || scored.semanticScore() >= SEMANTIC_MIN_SCORE)
                .sorted(resultComparator())
                .limit(limit)
                .map(ScoredEntry::entry)
                .toList();
    }

    /**
     * 写入解析使用的候选召回。只负责找“可能谈论同一事实”的旧 active memory，
     * 不直接决定 duplicate/supersede。
     */
    List<MemoryEntry> retrieveWriteCandidates(String query,
                                              int limit,
                                              List<MemoryEntry> domainEntries,
                                              MemoryEntry.MemoryType type) {
        if (query == null || query.isBlank() || limit <= 0
                || domainEntries == null || domainEntries.isEmpty()) {
            return List.of();
        }
        return score(query, domainEntries, true).stream()
                .filter(scored -> scored.entry().getType() == type)
                .filter(scored -> scored.lexicalScore() > 0
                        || scored.semanticScore() >= WRITE_CANDIDATE_MIN_SCORE)
                .sorted(Comparator.comparingDouble(ScoredEntry::hybridRelevance).reversed()
                        .thenComparing((ScoredEntry value) -> LongTermMemory.lastConfirmedAtOf(value.entry()),
                                Comparator.reverseOrder())
                        .thenComparing((ScoredEntry value) -> value.entry().getTimestamp(),
                                Comparator.reverseOrder())
                        .thenComparing(value -> value.entry().getId()))
                .limit(limit)
                .map(ScoredEntry::entry)
                .toList();
    }

    public String buildContextForQuery(String query, int maxTokens) {
        return buildContextForQuery(query, maxTokens, null);
    }

    public String buildContextForQuery(String query, int maxTokens, String projectKey) {
        List<MemoryEntry> relevant = retrieveLongTerm(query, 10, projectKey);
        if (relevant.isEmpty() || maxTokens <= 0) return "";

        StringBuilder body = new StringBuilder();
        int usedTokens = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) {
                continue;
            }
            body.append("- [").append(entry.getType()).append("] ")
                    .append(entry.getContent()).append("\n");
            usedTokens += entry.getTokenCount();
        }
        if (body.isEmpty()) {
            return "";
        }
        return "## 相关长期记忆\n\n" + body + "\n";
    }

    double lexicalScore(MemoryEntry entry, String query) {
        if (entry == null || query == null || query.isBlank()) {
            return 0.0d;
        }
        String contentLower = entry.getContent().toLowerCase(Locale.ROOT);
        String queryLower = query.toLowerCase(Locale.ROOT).trim();
        if (!queryLower.isEmpty() && contentLower.contains(queryLower)) {
            return 1.0d;
        }

        Set<String> queryWords = MemoryQueryTokenizer.tokenize(queryLower);
        if (queryWords.isEmpty()) {
            return 0.0d;
        }
        int matchedWords = 0;
        for (String word : queryWords) {
            if (!word.isEmpty() && contentLower.contains(word)) {
                matchedWords++;
            }
        }
        return (double) matchedWords / queryWords.size();
    }

    double decayFactor(MemoryEntry entry) {
        return decayFactor(LongTermMemory.lastConfirmedAtOf(entry));
    }

    double decayFactor(Instant lastConfirmedAt) {
        if (lastConfirmedAt == null) {
            return DECAY_FLOOR;
        }
        double ageDays = Math.max(0.0d,
                Duration.between(lastConfirmedAt, clock.instant()).toMillis() / 86_400_000.0d);
        double signal = Math.pow(2.0d, -ageDays / DECAY_HALF_LIFE_DAYS);
        return DECAY_FLOOR + (1.0d - DECAY_FLOOR) * signal;
    }

    private List<ScoredEntry> score(String query,
                                    List<MemoryEntry> entries,
                                    boolean writeCandidateMode) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        Optional<float[]> queryVector = embeddingCache.embedQuery(query);
        Map<String, float[]> entryVectors = queryVector.isPresent()
                ? embeddingCache.embeddingsFor(entries)
                : Map.of();

        List<ScoredEntry> scored = new ArrayList<>(entries.size());
        for (MemoryEntry entry : entries) {
            double lexical = lexicalScore(entry, query);
            double semantic = -1.0d;
            if (queryVector.isPresent()) {
                float[] entryVector = entryVectors.get(entry.getId());
                if (entryVector != null) {
                    semantic = Math.max(0.0d,
                            Math.min(1.0d, cosineSimilarity(queryVector.get(), entryVector)));
                }
            }

            double hybrid = semantic >= 0.0d
                    ? LEXICAL_WEIGHT * lexical + SEMANTIC_WEIGHT * semantic
                    : lexical;
            double finalScore = writeCandidateMode
                    ? hybrid
                    : hybrid * decayFactor(entry);
            scored.add(new ScoredEntry(entry, lexical, semantic, hybrid, finalScore));
        }
        return scored;
    }

    private Comparator<ScoredEntry> resultComparator() {
        return Comparator.comparingDouble(ScoredEntry::finalScore).reversed()
                .thenComparing(Comparator.comparingDouble(ScoredEntry::hybridRelevance).reversed())
                .thenComparing((ScoredEntry value) -> LongTermMemory.lastConfirmedAtOf(value.entry()),
                        Comparator.reverseOrder())
                .thenComparing((ScoredEntry value) -> value.entry().getTimestamp(),
                        Comparator.reverseOrder())
                .thenComparing(value -> value.entry().getId());
    }

    static double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0.0d;
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    @Override
    public void close() {
        embeddingCache.close();
    }

    private record ScoredEntry(MemoryEntry entry,
                               double lexicalScore,
                               double semanticScore,
                               double hybridRelevance,
                               double finalScore) {
    }
}
