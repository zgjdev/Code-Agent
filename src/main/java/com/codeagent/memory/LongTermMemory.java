package com.codeagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨对话持久化的关键信息。
 *
 * <p>JSON 文件仍是唯一事实源；active/superseded 生命周期存放在 metadata 中。
 * legacy 条目没有 status 时按 active 处理。</p>
 */
public class LongTermMemory implements Memory {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_DIR_PROPERTY = "codeagent.memory.dir";
    private static final String STORAGE_DIR_ENV = "CODEAGENT_MEMORY_DIR";
    private static final String STORAGE_FILE = "long_term_memory.json";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_SUPERSEDED = "superseded";
    private static final String LAST_CONFIRMED_AT = "lastConfirmedAt";

    private final Map<String, MemoryEntry> entries;
    private final AtomicInteger tokenCounter;
    private final ObjectMapper mapper;
    private final File storageFile;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        this.entries = new ConcurrentHashMap<>();
        this.tokenCounter = new AtomicInteger(0);
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        File dir = storageDir;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.storageFile = new File(dir, STORAGE_FILE);
        loadFromDisk();
    }

    @Override
    public synchronized void store(MemoryEntry entry) {
        storeIfNovel(entry);
    }

    /**
     * 存储一条 active 记忆；只对确定性完全等价内容做 no-op。
     *
     * @return true 表示内存中发生写入，false 表示命中 exact duplicate。
     */
    public synchronized boolean storeIfNovel(MemoryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        boolean duplicate = entries.values().stream()
                .filter(LongTermMemory::isActive)
                .anyMatch(existing -> MemoryDeduplicator.isDuplicate(existing, entry));
        if (duplicate) {
            return false;
        }

        MemoryEntry normalized = withStatusAndConfirmation(entry, STATUS_ACTIVE);
        MemoryEntry previous = entries.put(normalized.getId(), normalized);
        if (previous != null) {
            tokenCounter.addAndGet(-previous.getTokenCount());
        }
        tokenCounter.addAndGet(normalized.getTokenCount());
        saveToDisk();
        return true;
    }

    /**
     * 刷新 active 记忆的显式确认时间；不改变正文和创建时间。
     *
     * <p>持久化失败时回滚内存状态，确认时间永不倒退。</p>
     */
    public synchronized boolean confirm(String targetId, Instant confirmedAt) {
        if (targetId == null || targetId.isBlank() || confirmedAt == null) {
            return false;
        }
        MemoryEntry existing = entries.get(targetId);
        if (existing == null || !isActive(existing)) {
            return false;
        }

        Instant current = lastConfirmedAtOf(existing);
        Instant effective = confirmedAt.isAfter(current) ? confirmedAt : current;
        if (effective.equals(current) && hasValidPersistedConfirmation(existing)) {
            return true;
        }

        Map<String, String> metadata = new HashMap<>(existing.getMetadata());
        metadata.put(LAST_CONFIRMED_AT, effective.toString());
        MemoryEntry confirmed = copyWithMetadata(existing, metadata);

        entries.put(targetId, confirmed);
        try {
            saveToDiskOrThrow();
            return true;
        } catch (IOException e) {
            entries.put(targetId, existing);
            log.warn("长期记忆确认时间持久化失败，已回滚: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 原子地让旧 active 记忆失效，并写入替代它的新 active 记忆。
     *
     * <p>任一校验或持久化失败都不会留下“新旧同时 active”的半更新内存状态。</p>
     */
    public synchronized boolean supersede(String targetId, MemoryEntry replacement) {
        if (targetId == null || targetId.isBlank() || replacement == null) {
            return false;
        }
        MemoryEntry existing = entries.get(targetId);
        if (existing == null || !isActive(existing)
                || !MemoryDeduplicator.sameDomain(existing, replacement)
                || targetId.equals(replacement.getId())
                || entries.containsKey(replacement.getId())) {
            return false;
        }

        Map<String, String> oldMetadata = new HashMap<>(existing.getMetadata());
        oldMetadata.put("status", STATUS_SUPERSEDED);
        oldMetadata.put("supersededBy", replacement.getId());
        MemoryEntry superseded = copyWithMetadata(existing, oldMetadata);

        Map<String, String> newMetadata = new HashMap<>(replacement.getMetadata());
        newMetadata.put("status", STATUS_ACTIVE);
        newMetadata.putIfAbsent(LAST_CONFIRMED_AT, replacement.getTimestamp().toString());
        newMetadata.put("supersedes", targetId);
        MemoryEntry activeReplacement = copyWithMetadata(replacement, newMetadata);

        int tokenSnapshot = tokenCounter.get();
        entries.put(targetId, superseded);
        entries.put(activeReplacement.getId(), activeReplacement);
        tokenCounter.addAndGet(activeReplacement.getTokenCount());
        try {
            saveToDiskOrThrow();
            return true;
        } catch (IOException e) {
            entries.put(targetId, existing);
            entries.remove(activeReplacement.getId());
            tokenCounter.set(tokenSnapshot);
            log.warn("长期记忆更新持久化失败，已回滚: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        return search(query, limit, null);
    }

    /** legacy 词法搜索入口；只返回当前仍 active 的可见记忆。 */
    public List<MemoryEntry> search(String query, int limit, String projectKey) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);
        return entries.values().stream()
                .filter(LongTermMemory::isActive)
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .filter(entry -> {
                    if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
                        return true;
                    }
                    return entry.getMetadata().values().stream()
                            .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    /** 保留历史可见性语义：包括 superseded 条目，供 list/audit 使用。 */
    public List<MemoryEntry> getAll(String projectKey) {
        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .collect(Collectors.toList());
    }

    /** 正常检索和写入关系解析只使用 active 记忆。 */
    public List<MemoryEntry> getActiveVisible(String projectKey) {
        return entries.values().stream()
                .filter(LongTermMemory::isActive)
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .collect(Collectors.toList());
    }

    @Override
    public synchronized boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            tokenCounter.addAndGet(-removed.getTokenCount());
            saveToDisk();
            return true;
        }
        return false;
    }

    @Override
    public synchronized void clear() {
        entries.clear();
        tokenCounter.set(0);
        saveToDisk();
    }

    @Override
    public int getTokenCount() {
        return tokenCounter.get();
    }

    @Override
    public int size() {
        return entries.size();
    }

    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .collect(Collectors.toList());
    }

    public static boolean isVisibleInProject(MemoryEntry entry, String projectKey) {
        String scope = scopeOf(entry);
        if ("global".equals(scope)) {
            return true;
        }
        String entryProject = entry.getMetadata().get("project");
        return projectKey != null && !projectKey.isBlank() && Objects.equals(entryProject, projectKey);
    }

    public static String scopeOf(MemoryEntry entry) {
        String scope = entry.getMetadata().get("scope");
        if ("project".equalsIgnoreCase(scope)) {
            return "project";
        }
        return "global";
    }

    public static boolean isActive(MemoryEntry entry) {
        if (entry == null) {
            return false;
        }
        String status = entry.getMetadata().get("status");
        return status == null || status.isBlank() || STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    public static String statusOf(MemoryEntry entry) {
        return isActive(entry) ? STATUS_ACTIVE : STATUS_SUPERSEDED;
    }

    private static boolean hasValidPersistedConfirmation(MemoryEntry entry) {
        String value = entry.getMetadata().get(LAST_CONFIRMED_AT);
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Instant parsed = Instant.parse(value);
            return !parsed.isBefore(entry.getTimestamp());
        } catch (Exception ignored) {
            return false;
        }
    }

    public static Instant lastConfirmedAtOf(MemoryEntry entry) {
        if (entry == null) {
            return Instant.EPOCH;
        }
        Instant createdAt = entry.getTimestamp();
        String value = entry.getMetadata().get(LAST_CONFIRMED_AT);
        if (value != null && !value.isBlank()) {
            try {
                Instant parsed = Instant.parse(value);
                return parsed.isAfter(createdAt) ? parsed : createdAt;
            } catch (Exception ignored) {
                // Legacy/corrupted metadata falls back to immutable creation time.
            }
        }
        return createdAt;
    }

    private static MemoryEntry withStatusAndConfirmation(MemoryEntry entry, String status) {
        Map<String, String> metadata = new HashMap<>(entry.getMetadata());
        metadata.put("status", status);
        metadata.put(LAST_CONFIRMED_AT, lastConfirmedAtOf(entry).toString());
        return copyWithMetadata(entry, metadata);
    }

    private static MemoryEntry copyWithMetadata(MemoryEntry entry, Map<String, String> metadata) {
        return new MemoryEntry(
                entry.getId(),
                entry.getContent(),
                entry.getType(),
                entry.getTimestamp(),
                Map.copyOf(metadata),
                entry.getTokenCount());
    }

    private void saveToDisk() {
        try {
            saveToDiskOrThrow();
        } catch (IOException e) {
            log.warn("长期记忆持久化失败: {}", e.getMessage(), e);
        }
    }

    private void saveToDiskOrThrow() throws IOException {
        List<Map<String, Object>> dataList = entries.values().stream()
                .sorted(Comparator.comparing(MemoryEntry::getId))
                .map(this::entryToMap)
                .collect(Collectors.toList());
        Path target = storageFile.toPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writeValue(temporary.toFile(), dataList);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static File resolveStorageDir() {
        String configuredDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            return new File(configuredDir);
        }
        return new File(new File(System.getProperty("user.home"), ".codeagent"), "memory");
    }

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!storageFile.exists()) return;

        try {
            List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);
                if (entry != null) {
                    entries.put(entry.getId(), entry);
                    tokenCounter.addAndGet(entry.getTokenCount());
                }
            }
            log.info("加载了 {} 条长期记忆", entries.size());
        } catch (IOException e) {
            log.warn("加载长期记忆失败: {}", e.getMessage(), e);
        }
    }

    private Map<String, Object> entryToMap(MemoryEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("content", entry.getContent());
        map.put("type", entry.getType().name());
        map.put("timestamp", entry.getTimestamp().toString());
        map.put("metadata", entry.getMetadata());
        map.put("tokenCount", entry.getTokenCount());
        return map;
    }

    @SuppressWarnings("unchecked")
    private MemoryEntry mapToEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf((String) map.get("type"));
            Instant timestamp = null;
            Object timestampObj = map.get("timestamp");
            if (timestampObj instanceof String timestampValue && !timestampValue.isBlank()) {
                timestamp = Instant.parse(timestampValue);
            }
            Map<String, String> metadata = new HashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }
            int tokenCount = map.get("tokenCount") instanceof Number n
                    ? n.intValue() : MemoryEntry.estimateTokens(content);
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount);
        } catch (Exception e) {
            return null;
        }
    }

    public String getStatusSummary() {
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));
        long active = entries.values().stream().filter(LongTermMemory::isActive).count();
        long superseded = entries.size() - active;

        return String.format("长期记忆: %d条 / %d tokens (active: %d, superseded: %d, 事实: %d, 摘要: %d, 工具结果: %d)",
                entries.size(), tokenCounter.get(), active, superseded,
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.TOOL_RESULT, 0L));
    }
}
