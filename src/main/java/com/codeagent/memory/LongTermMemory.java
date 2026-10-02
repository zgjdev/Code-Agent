package com.codeagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨对话持久化的关键信息。
 *
 * <p>SQLite 是唯一事实源；每个 MemoryEntry 独立持久化，active/superseded 生命周期
 * 由结构化列与 metadata 共同保存。语义 embedding 仍由 MemoryEmbeddingCache 在进程内派生。</p>
 */
public class LongTermMemory implements Memory {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_DIR_PROPERTY = "codeagent.memory.dir";
    private static final String STORAGE_DIR_ENV = "CODEAGENT_MEMORY_DIR";

    private final SqliteLongTermMemoryRepository repository;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        Objects.requireNonNull(storageDir, "storageDir");
        try {
            this.repository = new SqliteLongTermMemoryRepository(storageDir.toPath());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法初始化长期记忆 SQLite: " + storageDir.getAbsolutePath(), e);
        }
    }

    @Override
    public synchronized void store(MemoryEntry entry) {
        storeIfNovel(entry);
    }

    /**
     * 存储一条 active 记忆；只对确定性完全等价内容做 no-op。
     *
     * @return true 表示 SQLite 中发生写入，false 表示 exact duplicate 或持久化失败。
     */
    public synchronized boolean storeIfNovel(MemoryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        MemoryEntry normalized = LongTermMemorySemantics.withStatusAndConfirmation(
                entry,
                LongTermMemorySemantics.STATUS_ACTIVE);
        try {
            return repository.storeIfNovel(normalized);
        } catch (SQLException e) {
            log.warn("长期记忆持久化失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 刷新 active 记忆的显式确认时间；不改变正文和创建时间。
     */
    public synchronized boolean confirm(String targetId, Instant confirmedAt) {
        try {
            return repository.confirm(targetId, confirmedAt);
        } catch (SQLException e) {
            log.warn("长期记忆确认时间持久化失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 原子地让旧 active 记忆失效，并写入替代它的新 active 记忆。
     */
    public synchronized boolean supersede(String targetId, MemoryEntry replacement) {
        try {
            return repository.supersede(targetId, replacement);
        } catch (SQLException e) {
            log.warn("长期记忆更新持久化失败: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        try {
            return repository.findById(id);
        } catch (SQLException e) {
            throw readFailure(e);
        }
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        return search(query, limit, null);
    }

    /** legacy 词法搜索入口；只返回当前仍 active 的可见记忆。 */
    public List<MemoryEntry> search(String query, int limit, String projectKey) {
        if (limit <= 0) {
            return List.of();
        }
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);
        return getActiveVisible(projectKey).stream()
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
        try {
            return repository.findAll();
        } catch (SQLException e) {
            throw readFailure(e);
        }
    }

    /** 保留历史可见性语义：包括 superseded 条目，供 list/audit 使用。 */
    public List<MemoryEntry> getAll(String projectKey) {
        try {
            return repository.findVisible(projectKey, false);
        } catch (SQLException e) {
            throw readFailure(e);
        }
    }

    /** 正常检索和写入关系解析只使用 active 记忆。 */
    public List<MemoryEntry> getActiveVisible(String projectKey) {
        try {
            return repository.findVisible(projectKey, true);
        } catch (SQLException e) {
            throw readFailure(e);
        }
    }

    @Override
    public synchronized boolean delete(String id) {
        try {
            return repository.delete(id);
        } catch (SQLException e) {
            log.warn("删除长期记忆失败: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public synchronized void clear() {
        try {
            repository.clear();
        } catch (SQLException e) {
            log.warn("清空长期记忆失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public int getTokenCount() {
        try {
            return repository.tokenCount();
        } catch (SQLException e) {
            throw readFailure(e);
        }
    }

    @Override
    public int size() {
        try {
            return repository.size();
        } catch (SQLException e) {
            throw readFailure(e);
        }
    }

    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        try {
            return repository.findByType(type);
        } catch (SQLException e) {
            throw readFailure(e);
        }
    }

    public static boolean isVisibleInProject(MemoryEntry entry, String projectKey) {
        return LongTermMemorySemantics.isVisibleInProject(entry, projectKey);
    }

    public static String scopeOf(MemoryEntry entry) {
        return LongTermMemorySemantics.scopeOf(entry);
    }

    public static boolean isActive(MemoryEntry entry) {
        return LongTermMemorySemantics.isActive(entry);
    }

    public static String statusOf(MemoryEntry entry) {
        return LongTermMemorySemantics.statusOf(entry);
    }

    public static Instant lastConfirmedAtOf(MemoryEntry entry) {
        return LongTermMemorySemantics.lastConfirmedAtOf(entry);
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

    private IllegalStateException readFailure(SQLException e) {
        log.warn("读取长期记忆 SQLite 失败: {}", e.getMessage(), e);
        return new IllegalStateException("读取长期记忆 SQLite 失败", e);
    }

    public String getStatusSummary() {
        List<MemoryEntry> entries = getAll();
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));
        long active = entries.stream().filter(LongTermMemory::isActive).count();
        long superseded = entries.size() - active;
        int tokens = entries.stream().mapToInt(MemoryEntry::getTokenCount).sum();

        return String.format(
                "长期记忆: %d条 / %d tokens (active: %d, superseded: %d, 事实: %d, 摘要: %d, 工具结果: %d)",
                entries.size(),
                tokens,
                active,
                superseded,
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.TOOL_RESULT, 0L));
    }
}
