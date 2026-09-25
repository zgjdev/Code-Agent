package com.codeagent.memory;

import com.codeagent.rag.embedding.EmbeddingException;
import com.codeagent.rag.embedding.EmbeddingProvider;
import com.codeagent.rag.embedding.InProcessBgeEmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期记忆的进程内 embedding 缓存。
 *
 * <p>只复用本地 EmbeddingProvider，不持久化向量。缓存 key 同时包含 memory id、
 * content hash 和 embedding space，正文或模型版本变化都会自然失效。</p>
 */
public final class MemoryEmbeddingCache implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingCache.class);

    private final EmbeddingProvider provider;
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();

    public MemoryEmbeddingCache() {
        this(new InProcessBgeEmbeddingProvider());
    }

    MemoryEmbeddingCache(EmbeddingProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public Optional<float[]> embedQuery(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        try {
            List<float[]> vectors = provider.embedAll(List.of(query));
            if (vectors.size() != 1 || vectors.get(0) == null) {
                return Optional.empty();
            }
            return Optional.of(vectors.get(0).clone());
        } catch (EmbeddingException | RuntimeException e) {
            log.warn("长期记忆 query embedding 失败，降级为词法检索: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 返回当前 entries 的向量。缺失项会一次批量计算，已缓存项不会重复计算。
     */
    public Map<String, float[]> embeddingsFor(List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Map.of();
        }

        Map<String, float[]> result = new LinkedHashMap<>();
        List<MemoryEntry> missingEntries = new ArrayList<>();
        List<String> missingKeys = new ArrayList<>();

        for (MemoryEntry entry : entries) {
            if (entry == null) continue;
            String key = cacheKey(entry);
            float[] cached = cache.get(key);
            if (cached != null) {
                result.put(entry.getId(), cached.clone());
            } else {
                missingEntries.add(entry);
                missingKeys.add(key);
            }
        }

        if (!missingEntries.isEmpty()) {
            try {
                List<String> inputs = missingEntries.stream()
                        .map(MemoryEntry::getContent)
                        .toList();
                List<float[]> vectors = provider.embedAll(inputs);
                if (vectors.size() != missingEntries.size()) {
                    throw new EmbeddingException(
                            "memory_embedding_count_mismatch",
                            "Local embedding returned an unexpected number of vectors");
                }
                for (int index = 0; index < missingEntries.size(); index++) {
                    float[] vector = vectors.get(index);
                    if (vector == null || vector.length != provider.space().dimension()) {
                        throw new EmbeddingException(
                                "memory_embedding_dimension_mismatch",
                                "Local embedding returned an unexpected vector dimension");
                    }
                    float[] copy = vector.clone();
                    cache.put(missingKeys.get(index), copy);
                    result.put(missingEntries.get(index).getId(), copy.clone());
                }
            } catch (EmbeddingException | RuntimeException e) {
                log.warn("长期记忆 entry embedding 失败，未命中缓存的条目将使用词法分数: {}", e.getMessage());
            }
        }

        return Map.copyOf(result);
    }

    int cachedEntryCount() {
        return cache.size();
    }

    String embeddingSpaceId() {
        return provider.space().embeddingSpaceId();
    }

    private String cacheKey(MemoryEntry entry) {
        return entry.getId() + "\u0000" + sha256(entry.getContent())
                + "\u0000" + provider.space().embeddingSpaceId();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Override
    public void close() {
        try {
            provider.close();
        } catch (Exception e) {
            log.debug("关闭长期记忆 embedding provider 失败", e);
        } finally {
            cache.clear();
        }
    }
}
