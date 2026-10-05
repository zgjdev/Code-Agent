package com.codeagent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

final class SqliteLongTermMemoryRepository {
    private static final Logger log = LoggerFactory.getLogger(SqliteLongTermMemoryRepository.class);

    static final String DATABASE_FILE = "memory.db";
    static final String LEGACY_JSON_FILE = "long_term_memory.json";
    static final String LEGACY_BACKUP_FILE = "long_term_memory.json.migrated.bak";

    private static final String META_SCHEMA_VERSION = "schema_version";
    private static final String META_LEGACY_MIGRATION = "legacy_json_migration";
    private static final String SCHEMA_VERSION = "1";

    private final Path databasePath;
    private final Path legacyJsonPath;
    private final ObjectMapper mapper = new ObjectMapper();

    SqliteLongTermMemoryRepository(Path storageDir) throws IOException, SQLException {
        Files.createDirectories(storageDir);
        this.databasePath = storageDir.resolve(DATABASE_FILE);
        this.legacyJsonPath = storageDir.resolve(LEGACY_JSON_FILE);
        initializeSchema();
        migrateLegacyJsonIfNeeded();
    }

    Optional<MemoryEntry> findById(String id) throws SQLException {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = openConnection()) {
            return findById(connection, id);
        }
    }

    List<MemoryEntry> findAll() throws SQLException {
        return queryList("SELECT * FROM long_term_memories ORDER BY id", statement -> {
        });
    }

    List<MemoryEntry> findVisible(String projectKey, boolean activeOnly) throws SQLException {
        boolean hasProject = projectKey != null && !projectKey.isBlank();
        StringBuilder sql = new StringBuilder("SELECT * FROM long_term_memories WHERE ");
        if (hasProject) {
            sql.append("(scope='global' OR (scope='project' AND project_key=?))");
        } else {
            sql.append("scope='global'");
        }
        if (activeOnly) {
            sql.append(" AND status='active'");
        }
        sql.append(" ORDER BY id");

        return queryList(sql.toString(), statement -> {
            if (hasProject) {
                statement.setString(1, projectKey);
            }
        });
    }

    List<MemoryEntry> findByType(MemoryEntry.MemoryType type) throws SQLException {
        if (type == null) {
            return List.of();
        }
        return queryList(
                "SELECT * FROM long_term_memories WHERE type=? ORDER BY id",
                statement -> statement.setString(1, type.name()));
    }

    int size() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM long_term_memories")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    int tokenCount() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(SUM(token_count), 0) FROM long_term_memories")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    boolean storeIfNovel(MemoryEntry entry) throws SQLException {
        return inImmediateTransaction(connection -> {
            if (hasExactDuplicate(connection, entry)) {
                return false;
            }
            upsert(connection, entry);
            return true;
        });
    }

    boolean confirm(String targetId, Instant confirmedAt) throws SQLException {
        if (targetId == null || targetId.isBlank() || confirmedAt == null) {
            return false;
        }

        return inImmediateTransaction(connection -> {
            Optional<MemoryEntry> existingOptional = findById(connection, targetId);
            if (existingOptional.isEmpty()) {
                return false;
            }
            MemoryEntry existing = existingOptional.get();
            if (!LongTermMemorySemantics.isActive(existing)) {
                return false;
            }

            Instant current = LongTermMemorySemantics.lastConfirmedAtOf(existing);
            Instant effective = confirmedAt.isAfter(current) ? confirmedAt : current;
            if (effective.equals(current)
                    && LongTermMemorySemantics.hasValidPersistedConfirmation(existing)) {
                return true;
            }

            Map<String, String> metadata = new HashMap<>(existing.getMetadata());
            metadata.put(LongTermMemorySemantics.LAST_CONFIRMED_AT, effective.toString());
            upsert(connection, LongTermMemorySemantics.copyWithMetadata(existing, metadata));
            return true;
        });
    }

    boolean supersede(String targetId, MemoryEntry replacement) throws SQLException {
        if (targetId == null || targetId.isBlank() || replacement == null) {
            return false;
        }

        return inImmediateTransaction(connection -> {
            Optional<MemoryEntry> existingOptional = findById(connection, targetId);
            if (existingOptional.isEmpty()) {
                return false;
            }
            MemoryEntry existing = existingOptional.get();
            if (!LongTermMemorySemantics.isActive(existing)
                    || !MemoryDeduplicator.sameDomain(existing, replacement)
                    || targetId.equals(replacement.getId())
                    || findById(connection, replacement.getId()).isPresent()) {
                return false;
            }

            Map<String, String> oldMetadata = new HashMap<>(existing.getMetadata());
            oldMetadata.put("status", LongTermMemorySemantics.STATUS_SUPERSEDED);
            oldMetadata.put("supersededBy", replacement.getId());
            MemoryEntry superseded =
                    LongTermMemorySemantics.copyWithMetadata(existing, oldMetadata);

            Map<String, String> newMetadata = new HashMap<>(replacement.getMetadata());
            newMetadata.put("status", LongTermMemorySemantics.STATUS_ACTIVE);
            newMetadata.put(
                    LongTermMemorySemantics.LAST_CONFIRMED_AT,
                    LongTermMemorySemantics.lastConfirmedAtOf(replacement).toString());
            newMetadata.put("supersedes", targetId);
            newMetadata.remove("supersededBy");
            MemoryEntry activeReplacement =
                    LongTermMemorySemantics.copyWithMetadata(replacement, newMetadata);

            upsert(connection, superseded);
            upsert(connection, activeReplacement);
            return true;
        });
    }

    boolean delete(String id) throws SQLException {
        if (id == null || id.isBlank()) {
            return false;
        }
        return inImmediateTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM long_term_memories WHERE id=?")) {
                statement.setString(1, id);
                return statement.executeUpdate() > 0;
            }
        });
    }

    void clear() throws SQLException {
        inImmediateTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM long_term_memories");
            }
            return null;
        });
    }

    private void initializeSchema() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS memory_meta (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS long_term_memories (
                      id TEXT PRIMARY KEY,
                      content TEXT NOT NULL,
                      type TEXT NOT NULL,
                      scope TEXT NOT NULL,
                      project_key TEXT,
                      status TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      last_confirmed_at TEXT NOT NULL,
                      supersedes TEXT,
                      superseded_by TEXT,
                      token_count INTEGER NOT NULL,
                      metadata_json TEXT NOT NULL,
                      canonical_content TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      version INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_long_term_memories_visible
                    ON long_term_memories(status, scope, project_key)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_long_term_memories_confirmed
                    ON long_term_memories(last_confirmed_at)
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_long_term_memory_active_global
                    ON long_term_memories(type, canonical_content)
                    WHERE status='active' AND scope='global'
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_long_term_memory_active_project
                    ON long_term_memories(type, project_key, canonical_content)
                    WHERE status='active'
                      AND scope='project'
                      AND project_key IS NOT NULL
                      AND project_key <> ''
                    """);
            putMeta(connection, META_SCHEMA_VERSION, SCHEMA_VERSION);
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databasePath.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    private void migrateLegacyJsonIfNeeded() throws IOException, SQLException {
        if (readMeta(META_LEGACY_MIGRATION).isPresent()) {
            return;
        }

        boolean legacyExists = Files.isRegularFile(legacyJsonPath);
        List<MemoryEntry> legacyEntries = List.of();
        if (legacyExists) {
            try {
                legacyEntries = readLegacyEntries();
            } catch (NoSuchFileException e) {
                if (readMeta(META_LEGACY_MIGRATION).isPresent()) {
                    return;
                }
                throw e;
            } catch (IOException e) {
                if (readMeta(META_LEGACY_MIGRATION).isPresent()) {
                    return;
                }
                throw e;
            }
        }

        List<MemoryEntry> entriesToMigrate = legacyEntries;
        boolean migratedByThisProcess = inImmediateTransaction(connection -> {
            if (readMeta(connection, META_LEGACY_MIGRATION).isPresent()) {
                return false;
            }
            if (legacyExists) {
                migrateLegacyEntries(connection, entriesToMigrate);
                putMeta(connection, META_LEGACY_MIGRATION, "migrated");
            } else {
                putMeta(connection, META_LEGACY_MIGRATION, "absent");
            }
            return true;
        });

        if (legacyExists && migratedByThisProcess) {
            Path backup = legacyJsonPath.resolveSibling(LEGACY_BACKUP_FILE);
            try {
                Files.move(
                        legacyJsonPath,
                        backup,
                        StandardCopyOption.REPLACE_EXISTING);
                log.info("长期记忆已从 legacy JSON 迁移到 SQLite，旧文件保留为 {}", backup);
            } catch (NoSuchFileException ignored) {
                // Another process may have already moved the same file after committing.
            } catch (IOException e) {
                log.warn(
                        "长期记忆已迁移到 SQLite，但 legacy JSON 备份重命名失败: {}",
                        e.getMessage(),
                        e);
            }
        }
    }

    private List<MemoryEntry> readLegacyEntries() throws IOException {
        List<Map<String, Object>> data = mapper.readValue(
                legacyJsonPath.toFile(),
                new TypeReference<List<Map<String, Object>>>() {
                });
        List<MemoryEntry> entries = new ArrayList<>();
        for (Map<String, Object> item : data) {
            MemoryEntry entry = legacyMapToEntry(item);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private MemoryEntry legacyMapToEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryEntry.MemoryType type =
                    MemoryEntry.MemoryType.valueOf((String) map.get("type"));

            Instant timestamp = null;
            Object timestampValue = map.get("timestamp");
            if (timestampValue instanceof String value && !value.isBlank()) {
                timestamp = Instant.parse(value);
            }

            Map<String, String> metadata = new HashMap<>();
            Object metadataValue = map.get("metadata");
            if (metadataValue instanceof Map<?, ?> rawMetadata) {
                rawMetadata.forEach((key, value) ->
                        metadata.put(String.valueOf(key), String.valueOf(value)));
            }

            int tokenCount = map.get("tokenCount") instanceof Number number
                    ? number.intValue()
                    : MemoryEntry.estimateTokens(content);
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount);
        } catch (Exception e) {
            log.warn("跳过无法解析的 legacy 长期记忆条目: {}", e.getMessage());
            return null;
        }
    }

    private MemoryEntry normalizeLegacyEntry(MemoryEntry entry) {
        String status = LongTermMemorySemantics.statusOf(entry);
        Map<String, String> metadata = new HashMap<>(entry.getMetadata());
        metadata.put("status", status);
        metadata.put(
                LongTermMemorySemantics.LAST_CONFIRMED_AT,
                LongTermMemorySemantics.lastConfirmedAtOf(entry).toString());
        return LongTermMemorySemantics.copyWithMetadata(entry, metadata);
    }

    private void migrateLegacyEntries(Connection connection, List<MemoryEntry> legacyEntries)
            throws SQLException {
        Map<String, MemoryEntry> existing = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM long_term_memories")) {
            while (result.next()) {
                MemoryEntry entry = fromRow(result);
                existing.put(entry.getId(), entry);
            }
        }
        Map<String, MemoryEntry> entries = new TreeMap<>(existing);
        Set<String> legacyIds = new HashSet<>();
        for (MemoryEntry entry : legacyEntries) {
            if (existing.containsKey(entry.getId())) {
                throw new SQLException("Legacy memory migration has a cross-source ID conflict");
            }
            entries.put(entry.getId(), normalizeLegacyEntry(entry));
            legacyIds.add(entry.getId());
        }

        List<MemoryEntry> ordered = entries.values().stream()
                .sorted(Comparator.comparing((MemoryEntry entry) -> !existing.containsKey(entry.getId()))
                        .thenComparing(MemoryEntry::getId))
                .toList();
        Map<String, List<MemoryEntry>> groups = new LinkedHashMap<>();
        Map<String, String> retainedIds = new HashMap<>();
        for (MemoryEntry entry : ordered) {
            String retainedId = entry.getId();
            if (LongTermMemorySemantics.isActive(entry)) {
                retainedId = groups.values().stream()
                        .map(group -> group.get(0))
                        .filter(LongTermMemorySemantics::isActive)
                        .filter(candidate -> MemoryDeduplicator.isDuplicate(candidate, entry))
                        .map(MemoryEntry::getId)
                        .findFirst()
                        .orElse(retainedId);
            }
            groups.computeIfAbsent(retainedId, ignored -> new ArrayList<>()).add(entry);
            retainedIds.put(entry.getId(), retainedId);
        }

        List<MemoryEntry> mergedEntries = new ArrayList<>();
        for (List<MemoryEntry> group : groups.values()) {
            MemoryEntry retained = group.get(0);
            Map<String, String> metadata = new HashMap<>(retained.getMetadata());
            Instant confirmedAt = group.stream()
                    .map(LongTermMemorySemantics::lastConfirmedAtOf)
                    .max(Instant::compareTo)
                    .orElseThrow();
            metadata.put(LongTermMemorySemantics.LAST_CONFIRMED_AT, confirmedAt.toString());
            mergeLegacyRelation(group, "supersedes", retainedIds, metadata);
            mergeLegacyRelation(group, "supersededBy", retainedIds, metadata);
            mergedEntries.add(LongTermMemorySemantics.copyWithMetadata(retained, metadata));
        }

        for (MemoryEntry entry : mergedEntries) {
            MemoryEntry previous = existing.get(entry.getId());
            if (legacyIds.contains(entry.getId()) || previous == null
                    || !previous.getMetadata().equals(entry.getMetadata())) {
                upsert(connection, entry);
            }
        }
    }

    private void mergeLegacyRelation(List<MemoryEntry> group,
                                     String relation,
                                     Map<String, String> retainedIds,
                                     Map<String, String> metadata) throws SQLException {
        String retainedId = group.get(0).getId();
        Set<String> targets = new HashSet<>();
        Set<String> unresolvedTargets = new HashSet<>();
        for (MemoryEntry entry : group) {
            String originalTarget = blankToNull(entry.getMetadata().get(relation));
            if (originalTarget == null) {
                continue;
            }
            String target = retainedIds.get(originalTarget);
            if (target == null) {
                unresolvedTargets.add(originalTarget);
            } else if (!retainedId.equals(target)) {
                targets.add(target);
            }
        }
        if (targets.isEmpty()) {
            targets.addAll(unresolvedTargets);
        }
        if (targets.size() > 1) {
            throw new SQLException("Legacy memory migration has conflicting " + relation + " relations");
        }
        metadata.remove(relation);
        if (!targets.isEmpty()) {
            metadata.put(relation, targets.iterator().next());
        }
    }

    private boolean hasExactDuplicate(Connection connection, MemoryEntry entry)
            throws SQLException {
        if (!LongTermMemorySemantics.isActive(entry)) {
            return false;
        }

        String scope = LongTermMemorySemantics.scopeOf(entry);
        String canonical = MemoryDeduplicator.canonicalize(entry.getContent());
        String projectKey = null;
        String sql;
        if ("project".equals(scope)) {
            projectKey = entry.getMetadata().get("project");
            if (projectKey == null || projectKey.isBlank()) {
                return false;
            }
            sql = """
                    SELECT 1 FROM long_term_memories
                    WHERE status='active'
                      AND type=?
                      AND scope='project'
                      AND project_key=?
                      AND canonical_content=?
                    LIMIT 1
                    """;
        } else {
            sql = """
                    SELECT 1 FROM long_term_memories
                    WHERE status='active'
                      AND type=?
                      AND scope='global'
                      AND canonical_content=?
                    LIMIT 1
                    """;
        }

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.getType().name());
            if ("project".equals(scope)) {
                statement.setString(2, projectKey);
                statement.setString(3, canonical);
            } else {
                statement.setString(2, canonical);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void upsert(Connection connection, MemoryEntry entry) throws SQLException {
        String scope = LongTermMemorySemantics.scopeOf(entry);
        String projectKey = "project".equals(scope)
                ? blankToNull(entry.getMetadata().get("project"))
                : null;
        String status = LongTermMemorySemantics.statusOf(entry);
        Instant lastConfirmedAt = LongTermMemorySemantics.lastConfirmedAtOf(entry);
        String supersedes = blankToNull(entry.getMetadata().get("supersedes"));
        String supersededBy = blankToNull(entry.getMetadata().get("supersededBy"));
        Map<String, String> normalizedMetadata = normalizedMetadata(
                entry,
                scope,
                projectKey,
                status,
                lastConfirmedAt,
                supersedes,
                supersededBy);

        String sql = """
                INSERT INTO long_term_memories(
                  id, content, type, scope, project_key, status, created_at,
                  last_confirmed_at, supersedes, superseded_by, token_count,
                  metadata_json, canonical_content, updated_at, version
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
                ON CONFLICT(id) DO UPDATE SET
                  content=excluded.content,
                  type=excluded.type,
                  scope=excluded.scope,
                  project_key=excluded.project_key,
                  status=excluded.status,
                  created_at=excluded.created_at,
                  last_confirmed_at=excluded.last_confirmed_at,
                  supersedes=excluded.supersedes,
                  superseded_by=excluded.superseded_by,
                  token_count=excluded.token_count,
                  metadata_json=excluded.metadata_json,
                  canonical_content=excluded.canonical_content,
                  updated_at=excluded.updated_at,
                  version=long_term_memories.version+1
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, entry.getId());
            statement.setString(index++, entry.getContent());
            statement.setString(index++, entry.getType().name());
            statement.setString(index++, scope);
            statement.setString(index++, projectKey);
            statement.setString(index++, status);
            statement.setString(index++, entry.getTimestamp().toString());
            statement.setString(index++, lastConfirmedAt.toString());
            statement.setString(index++, supersedes);
            statement.setString(index++, supersededBy);
            statement.setInt(index++, entry.getTokenCount());
            statement.setString(index++, serializeMetadata(normalizedMetadata));
            statement.setString(index++, MemoryDeduplicator.canonicalize(entry.getContent()));
            statement.setString(index, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private Map<String, String> normalizedMetadata(
            MemoryEntry entry,
            String scope,
            String projectKey,
            String status,
            Instant lastConfirmedAt,
            String supersedes,
            String supersededBy) {
        Map<String, String> metadata = new HashMap<>(entry.getMetadata());
        metadata.put("scope", scope);
        metadata.put("status", status);
        metadata.put(LongTermMemorySemantics.LAST_CONFIRMED_AT, lastConfirmedAt.toString());

        if ("project".equals(scope) && projectKey != null) {
            metadata.put("project", projectKey);
        } else {
            metadata.remove("project");
        }

        if (supersedes != null) {
            metadata.put("supersedes", supersedes);
        } else {
            metadata.remove("supersedes");
        }
        if (supersededBy != null) {
            metadata.put("supersededBy", supersededBy);
        } else {
            metadata.remove("supersededBy");
        }
        return metadata;
    }

    private Optional<MemoryEntry> findById(Connection connection, String id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM long_term_memories WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(fromRow(result))
                        : Optional.empty();
            }
        }
    }

    private List<MemoryEntry> queryList(String sql, StatementBinder binder)
            throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                List<MemoryEntry> entries = new ArrayList<>();
                while (result.next()) {
                    entries.add(fromRow(result));
                }
                return List.copyOf(entries);
            }
        }
    }

    private MemoryEntry fromRow(ResultSet result) throws SQLException {
        Map<String, String> metadata =
                deserializeMetadata(result.getString("metadata_json"));
        String scope = result.getString("scope");
        String projectKey = result.getString("project_key");
        String status = result.getString("status");
        String lastConfirmedAt = result.getString("last_confirmed_at");
        String supersedes = result.getString("supersedes");
        String supersededBy = result.getString("superseded_by");

        metadata.put("scope", scope);
        metadata.put("status", status);
        metadata.put(LongTermMemorySemantics.LAST_CONFIRMED_AT, lastConfirmedAt);
        if ("project".equals(scope) && projectKey != null && !projectKey.isBlank()) {
            metadata.put("project", projectKey);
        } else {
            metadata.remove("project");
        }
        if (supersedes != null && !supersedes.isBlank()) {
            metadata.put("supersedes", supersedes);
        } else {
            metadata.remove("supersedes");
        }
        if (supersededBy != null && !supersededBy.isBlank()) {
            metadata.put("supersededBy", supersededBy);
        } else {
            metadata.remove("supersededBy");
        }

        return new MemoryEntry(
                result.getString("id"),
                result.getString("content"),
                MemoryEntry.MemoryType.valueOf(result.getString("type")),
                Instant.parse(result.getString("created_at")),
                Map.copyOf(metadata),
                result.getInt("token_count"));
    }

    private String serializeMetadata(Map<String, String> metadata)
            throws SQLException {
        try {
            return mapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize long-term memory metadata", e);
        }
    }

    private Map<String, String> deserializeMetadata(String json)
            throws SQLException {
        try {
            if (json == null || json.isBlank()) {
                return new HashMap<>();
            }
            Map<String, String> metadata = mapper.readValue(
                    json,
                    new TypeReference<Map<String, String>>() {
                    });
            return new HashMap<>(metadata);
        } catch (IOException e) {
            throw new SQLException("Failed to deserialize long-term memory metadata", e);
        }
    }

    private Optional<String> readMeta(String key) throws SQLException {
        try (Connection connection = openConnection()) {
            return readMeta(connection, key);
        }
    }

    private Optional<String> readMeta(Connection connection, String key)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM memory_meta WHERE key=?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.ofNullable(result.getString(1))
                        : Optional.empty();
            }
        }
    }

    private void putMeta(Connection connection, String key, String value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO memory_meta(key, value) VALUES(?,?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private <T> T inImmediateTransaction(SqlTransaction<T> transaction)
            throws SQLException {
        try (Connection connection = openConnection()) {
            executeTransactionControl(connection, "BEGIN IMMEDIATE");
            try {
                T result = transaction.execute(connection);
                executeTransactionControl(connection, "COMMIT");
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            }
        }
    }

    private void executeTransactionControl(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            executeTransactionControl(connection, "ROLLBACK");
        } catch (SQLException rollbackError) {
            log.warn("长期记忆 SQLite rollback 失败: {}", rollbackError.getMessage());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlTransaction<T> {
        T execute(Connection connection) throws SQLException;
    }
}
