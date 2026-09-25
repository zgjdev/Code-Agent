package com.codeagent.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 统一长期记忆写入解析：CREATE / DUPLICATE / SUPERSEDE。
 *
 * <p>确定性等价只作为 fast-path；embedding 只负责找候选；最终语义关系由
 * MemoryRelationClassifier 判断，任何异常都不会自动让旧记忆失效。</p>
 */
final class MemoryWriteResolver {
    enum Action {
        CREATED,
        DUPLICATE,
        SUPERSEDED
    }

    record WriteResult(Action action, MemoryEntry memory, MemoryEntry previous) {
        static WriteResult created(MemoryEntry memory) {
            return new WriteResult(Action.CREATED, memory, null);
        }

        static WriteResult duplicate(MemoryEntry memory) {
            return new WriteResult(Action.DUPLICATE, memory, null);
        }

        static WriteResult superseded(MemoryEntry memory, MemoryEntry previous) {
            return new WriteResult(Action.SUPERSEDED, memory, previous);
        }
    }

    private static final int RELATION_CANDIDATE_LIMIT = 5;

    private final LongTermMemory longTermMemory;
    private final MemoryRetriever retriever;
    private final MemoryRelationClassifier classifier;
    private final Clock clock;

    MemoryWriteResolver(LongTermMemory longTermMemory,
                        MemoryRetriever retriever,
                        MemoryRelationClassifier classifier) {
        this(longTermMemory, retriever, classifier, Clock.systemUTC());
    }

    MemoryWriteResolver(LongTermMemory longTermMemory,
                        MemoryRetriever retriever,
                        MemoryRelationClassifier classifier,
                        Clock clock) {
        this.longTermMemory = Objects.requireNonNull(longTermMemory, "longTermMemory");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized WriteResult resolveAndStore(String fact,
                                             String scope,
                                             String projectKey,
                                             String submittedUserInput) {
        String normalizedFact = fact == null ? "" : fact.trim();
        if (normalizedFact.isEmpty()) {
            throw new IllegalArgumentException("fact must not be blank");
        }

        Instant now = clock.instant();
        MemoryEntry incoming = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                normalizedFact,
                MemoryEntry.MemoryType.FACT,
                now,
                metadata(scope, projectKey, now),
                MemoryEntry.estimateTokens(normalizedFact));

        List<MemoryEntry> domainEntries = longTermMemory.getActiveVisible(projectKey).stream()
                .filter(entry -> MemoryDeduplicator.sameDomain(entry, incoming))
                .toList();

        MemoryEntry exact = domainEntries.stream()
                .filter(entry -> MemoryDeduplicator.isDuplicate(entry, incoming))
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return WriteResult.duplicate(confirmOrThrow(exact, now));
        }

        if (domainEntries.isEmpty()) {
            longTermMemory.storeIfNovel(incoming);
            return WriteResult.created(longTermMemory.retrieve(incoming.getId()).orElse(incoming));
        }

        List<MemoryEntry> candidates = retriever.retrieveWriteCandidates(
                normalizedFact,
                RELATION_CANDIDATE_LIMIT,
                domainEntries,
                incoming.getType());

        MemoryRelationClassifier.Decision decision = classifier.classify(
                submittedUserInput, normalizedFact, candidates);

        if (decision.action() == MemoryRelationClassifier.Action.DUPLICATE) {
            MemoryEntry target = validTarget(decision.targetId(), candidates, incoming);
            if (target != null) {
                return WriteResult.duplicate(confirmOrThrow(target, now));
            }
        }

        if (decision.action() == MemoryRelationClassifier.Action.SUPERSEDE) {
            MemoryEntry target = validTarget(decision.targetId(), candidates, incoming);
            String evidence = decision.evidence() == null ? "" : decision.evidence().trim();
            String source = submittedUserInput == null ? "" : submittedUserInput;
            if (target != null && !evidence.isEmpty() && source.contains(evidence)) {
                if (longTermMemory.supersede(target.getId(), incoming)) {
                    MemoryEntry stored = longTermMemory.retrieve(incoming.getId()).orElse(incoming);
                    return WriteResult.superseded(stored, target);
                }
                throw new IllegalStateException("长期记忆更新失败，旧记忆保持有效");
            }
        }

        boolean stored = longTermMemory.storeIfNovel(incoming);
        if (!stored) {
            MemoryEntry duplicate = longTermMemory.getActiveVisible(projectKey).stream()
                    .filter(entry -> MemoryDeduplicator.isDuplicate(entry, incoming))
                    .findFirst()
                    .orElse(incoming);
            return WriteResult.duplicate(confirmOrThrow(duplicate, now));
        }
        return WriteResult.created(longTermMemory.retrieve(incoming.getId()).orElse(incoming));
    }

    private MemoryEntry validTarget(String targetId,
                                    List<MemoryEntry> candidates,
                                    MemoryEntry incoming) {
        if (targetId == null || targetId.isBlank()) {
            return null;
        }
        return candidates.stream()
                .filter(LongTermMemory::isActive)
                .filter(candidate -> targetId.equals(candidate.getId()))
                .filter(candidate -> MemoryDeduplicator.sameDomain(candidate, incoming))
                .findFirst()
                .orElse(null);
    }

    private MemoryEntry confirmOrThrow(MemoryEntry target, Instant confirmedAt) {
        if (!longTermMemory.confirm(target.getId(), confirmedAt)) {
            throw new IllegalStateException("长期记忆确认时间更新失败，旧记忆保持不变");
        }
        return longTermMemory.retrieve(target.getId()).orElse(target);
    }

    private static Map<String, String> metadata(String scope, String projectKey, Instant confirmedAt) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("source", "fact");
        metadata.put("scope", "global".equals(scope) ? "global" : "project");
        metadata.put("status", "active");
        metadata.put("lastConfirmedAt", confirmedAt.toString());
        if (!"global".equals(scope) && projectKey != null && !projectKey.isBlank()) {
            metadata.put("project", projectKey);
        }
        return Map.copyOf(metadata);
    }
}
