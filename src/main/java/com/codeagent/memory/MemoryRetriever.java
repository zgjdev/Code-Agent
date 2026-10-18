package com.codeagent.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 长期记忆检索器：FTS5/BM25 与本地语义排名经 RRF 融合，再乘确认时间衰减。
 */
public class MemoryRetriever implements AutoCloseable {
    // Explicit write relation candidates retain the previous scoring contract.
    static final double LEXICAL_WEIGHT = 0.45d;
    static final double SEMANTIC_WEIGHT = 0.55d;
    static final double SEMANTIC_MIN_SCORE = 0.475d;
    static final double WRITE_CANDIDATE_MIN_SCORE = 0.45d;
    static final double DECAY_FLOOR = 0.60d;
    static final double DECAY_HALF_LIFE_DAYS = 30.0d;
    static final int RRF_K = 60;
    static final int CANDIDATE_LIMIT = 50;
    private static final int MAX_INJECTED_MEMORIES = 10;

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
        List<MemoryEntry> entries = longTermMemory.getActiveVisible(projectKey);
        if (entries.isEmpty()) return List.of();
        int candidateLimit = Math.max(CANDIDATE_LIMIT, limit);
        Map<String, MemoryEntry> byId = new HashMap<>();
        entries.forEach(entry -> byId.put(entry.getId(), entry));
        Map<String, Double> fused = new HashMap<>();
        addRanks(fused, longTermMemory.lexicalMatches(query, projectKey, candidateLimit).stream()
                .filter(byId::containsKey).toList());

        Optional<float[]> queryVector = embeddingCache.embedQuery(query);
        if (queryVector.isPresent()) {
            Map<String, float[]> vectors = embeddingCache.embeddingsFor(entries, longTermMemory);
            List<String> semantic = entries.stream()
                    .filter(entry -> vectors.containsKey(entry.getId()))
                    .map(entry -> new SemanticMatch(entry.getId(), cosineSimilarity(queryVector.get(), vectors.get(entry.getId()))))
                    .filter(match -> match.score() >= SEMANTIC_MIN_SCORE)
                    .sorted(Comparator.comparingDouble(SemanticMatch::score).reversed().thenComparing(SemanticMatch::id))
                    .limit(candidateLimit).map(SemanticMatch::id).toList();
            addRanks(fused, semantic);
        }
        return fused.entrySet().stream()
                .map(value -> new RankedEntry(byId.get(value.getKey()), value.getValue(),
                        value.getValue() * decayFactor(byId.get(value.getKey()))))
                .sorted(Comparator.comparingDouble(RankedEntry::score).reversed()
                        .thenComparing(Comparator.comparingDouble(RankedEntry::rrf).reversed())
                        .thenComparing(value -> LongTermMemory.lastConfirmedAtOf(value.entry()), Comparator.reverseOrder())
                        .thenComparing(value -> value.entry().getTimestamp(), Comparator.reverseOrder())
                        .thenComparing(value -> value.entry().getId()))
                .limit(limit).map(RankedEntry::entry).toList();
    }

    private static void addRanks(Map<String, Double> fused, List<String> ids) {
        for (int index = 0; index < ids.size(); index++) {
            fused.merge(ids.get(index), 1.0d / (RRF_K + index + 1), Double::sum);
        }
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
        return scoreWriteCandidates(query, domainEntries).stream()
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
        if (maxTokens <= 0) return "";
        List<MemoryEntry> relevant = retrieveLongTerm(query, CANDIDATE_LIMIT, projectKey);
        if (relevant.isEmpty()) return "";

        StringBuilder body = new StringBuilder();
        int usedTokens = 0;
        int included = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) {
                continue;
            }
            body.append("- [").append(entry.getType()).append("] ")
                    .append(entry.getContent()).append("\n");
            usedTokens += entry.getTokenCount();
            if (++included == MAX_INJECTED_MEMORIES) break;
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

    private List<ScoredEntry> scoreWriteCandidates(String query, List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        Optional<float[]> queryVector = embeddingCache.embedQuery(query);
        Map<String, float[]> entryVectors = queryVector.isPresent()
                ? embeddingCache.embeddingsFor(entries, longTermMemory)
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
            scored.add(new ScoredEntry(entry, lexical, semantic, hybrid));
        }
        return scored;
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
                               double hybridRelevance) {
    }

    private record SemanticMatch(String id, double score) {}
    private record RankedEntry(MemoryEntry entry, double rrf, double score) {}
}
