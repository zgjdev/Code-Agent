package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.RoutingSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RuntimeExecutionStore implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;
    private final Connection connection;

    public RuntimeExecutionStore(Path databasePath) throws SQLException, IOException {
        Path absolute = databasePath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Connection opened = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        try {
            this.connection = opened;
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 5000");
            }
            migrate();
        } catch (SQLException | RuntimeException exception) {
            opened.close();
            throw exception;
        }
    }

    public synchronized RuntimeExecution enqueue(
            Path workspace, String sessionId, String submittedInput, ExecutionMode explicitMode) throws SQLException {
        requireText(sessionId, "sessionId");
        requireText(submittedInput, "submittedInput");
        String normalizedWorkspace = normalizeWorkspace(workspace);
        return inTransaction(() -> {
            long ordinal;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COALESCE(MAX(ordinal), -1) + 1
                    FROM runtime_executions
                    WHERE session_id = ? AND legacy_unbound = 0
                    """)) {
                statement.setString(1, sessionId);
                try (ResultSet rows = statement.executeQuery()) {
                    ordinal = rows.next() ? rows.getLong(1) : 0L;
                }
            }
            String id = "exec_" + UUID.randomUUID().toString().replace("-", "");
            Instant now = Instant.now();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO runtime_executions(
                        id, workspace, session_id, ordinal, status, submitted_input, explicit_mode,
                        attempt, legacy_unbound, created_at, updated_at)
                    VALUES(?, ?, ?, ?, 'enqueued', ?, ?, 0, 0, ?, ?)
                    """)) {
                statement.setString(1, id);
                statement.setString(2, normalizedWorkspace);
                statement.setString(3, sessionId);
                statement.setLong(4, ordinal);
                statement.setString(5, submittedInput);
                statement.setString(6, encode(explicitMode));
                statement.setString(7, now.toString());
                statement.setString(8, now.toString());
                statement.executeUpdate();
            }
            return requireFound(id);
        });
    }

    public synchronized RuntimeExecution adoptPlanExecution(
            String executionId, Path workspace, String sessionId, String submittedInput) throws SQLException {
        requireText(executionId, "executionId");
        requireText(sessionId, "sessionId");
        requireText(submittedInput, "submittedInput");
        String normalizedWorkspace = normalizeWorkspace(workspace);
        return inTransaction(() -> {
            Optional<RuntimeExecution> existing = find(executionId);
            if (existing.isPresent()) {
                RuntimeExecution value = existing.orElseThrow();
                if (!normalizedWorkspace.equals(value.workspace())
                        || !sessionId.equals(value.sessionId())
                        || !submittedInput.equals(value.submittedInput())
                        || value.explicitMode() != ExecutionMode.PLAN) {
                    throw new IllegalStateException(
                            "Legacy Plan execution identity conflicts with existing row: " + executionId);
                }
                return value;
            }
            long ordinal;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COALESCE(MAX(ordinal), -1) + 1
                    FROM runtime_executions
                    WHERE session_id = ? AND legacy_unbound = 0
                    """)) {
                statement.setString(1, sessionId);
                try (ResultSet rows = statement.executeQuery()) {
                    ordinal = rows.next() ? rows.getLong(1) : 0L;
                }
            }
            Instant now = Instant.now();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO runtime_executions(
                        id, workspace, session_id, ordinal, status, submitted_input, explicit_mode,
                        attempt, legacy_unbound, created_at, updated_at)
                    VALUES(?, ?, ?, ?, 'enqueued', ?, 'plan', 0, 0, ?, ?)
                    """)) {
                statement.setString(1, executionId);
                statement.setString(2, normalizedWorkspace);
                statement.setString(3, sessionId);
                statement.setLong(4, ordinal);
                statement.setString(5, submittedInput);
                statement.setString(6, now.toString());
                statement.setString(7, now.toString());
                statement.executeUpdate();
            }
            return requireFound(executionId);
        });
    }

    public synchronized Optional<RuntimeExecution> find(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM runtime_executions WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        }
    }

    public synchronized List<RuntimeExecution> list(Path workspace, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 100));
        List<RuntimeExecution> executions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM runtime_executions
                WHERE workspace = ? AND legacy_unbound = 0
                ORDER BY created_at DESC, ordinal DESC, id DESC
                LIMIT ?
                """)) {
            statement.setString(1, normalizeWorkspace(workspace));
            statement.setInt(2, bounded);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    executions.add(map(rows));
                }
            }
        }
        return List.copyOf(executions);
    }

    public synchronized boolean hasNonTerminal(String sessionId) throws SQLException {
        requireText(sessionId, "sessionId");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM runtime_executions
                WHERE session_id = ? AND legacy_unbound = 0
                  AND status IN ('enqueued','running')
                LIMIT 1
                """)) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    public synchronized Optional<String> running(Path workspace) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM runtime_executions
                WHERE workspace = ? AND legacy_unbound = 0 AND status = 'running'
                ORDER BY started_at, id
                LIMIT 1
                """)) {
            statement.setString(1, normalizeWorkspace(workspace));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        }
    }

    public synchronized Optional<RuntimeExecution> claimNext(Path workspace) throws SQLException {
        String normalizedWorkspace = normalizeWorkspace(workspace);
        return inTransaction(() -> {
            String id = null;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT candidate.id
                    FROM runtime_executions candidate
                    WHERE candidate.workspace = ?
                      AND candidate.status = 'enqueued'
                      AND candidate.legacy_unbound = 0
                      AND NOT EXISTS (
                          SELECT 1 FROM runtime_executions running
                          WHERE running.workspace = candidate.workspace
                            AND running.status = 'running'
                            AND running.legacy_unbound = 0
                      )
                      AND candidate.ordinal = (
                          SELECT MIN(head.ordinal)
                          FROM runtime_executions head
                          WHERE head.session_id = candidate.session_id
                            AND head.legacy_unbound = 0
                            AND head.status IN ('enqueued', 'running')
                      )
                    ORDER BY candidate.created_at, candidate.ordinal, candidate.id
                    LIMIT 1
                    """)) {
                statement.setString(1, normalizedWorkspace);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        id = rows.getString(1);
                    }
                }
            }
            if (id == null) {
                return Optional.empty();
            }
            Instant now = Instant.now();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE runtime_executions
                    SET status = 'running', started_at = COALESCE(started_at, ?),
                        updated_at = ?, attempt = attempt + 1
                    WHERE id = ? AND status = 'enqueued'
                    """)) {
                statement.setString(1, now.toString());
                statement.setString(2, now.toString());
                statement.setString(3, id);
                if (statement.executeUpdate() != 1) {
                    return Optional.empty();
                }
            }
            return Optional.of(requireFound(id));
        });
    }

    public synchronized RuntimeExecution commitRoutingDecision(
            String id, ExecutionMode selectedMode, RoutingSource routingSource) throws SQLException {
        if (selectedMode == null || routingSource == null) {
            throw new IllegalArgumentException("Routing decision must be complete");
        }
        return inTransaction(() -> {
            RuntimeExecution current = requireFound(id);
            if (current.status() != ExecutionStatus.RUNNING) {
                throw new IllegalStateException("Routing requires a running execution: " + id);
            }
            if (current.selectedMode() != null) {
                if (current.selectedMode() == selectedMode && current.routingSource() == routingSource) {
                    return current;
                }
                throw new IllegalStateException("Routing decision already committed for execution: " + id);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE runtime_executions
                    SET selected_mode = ?, routing_source = ?, updated_at = ?
                    WHERE id = ? AND status = 'running' AND selected_mode IS NULL
                    """)) {
                statement.setString(1, encode(selectedMode));
                statement.setString(2, encode(routingSource));
                statement.setString(3, Instant.now().toString());
                statement.setString(4, id);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Routing decision was concurrently changed: " + id);
                }
            }
            return requireFound(id);
        });
    }

    public synchronized RuntimeExecution commitResolvedInput(String id, String resolvedTaskInput) throws SQLException {
        requireText(resolvedTaskInput, "resolvedTaskInput");
        return inTransaction(() -> {
            RuntimeExecution current = requireFound(id);
            if (current.status() != ExecutionStatus.RUNNING) {
                throw new IllegalStateException("Input resolution requires a running execution: " + id);
            }
            if (current.resolvedTaskInput() != null) {
                if (current.resolvedTaskInput().equals(resolvedTaskInput)) {
                    return current;
                }
                throw new IllegalStateException("Resolved input already committed for execution: " + id);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE runtime_executions
                    SET resolved_task_input = ?, updated_at = ?
                    WHERE id = ? AND status = 'running' AND resolved_task_input IS NULL
                    """)) {
                statement.setString(1, resolvedTaskInput);
                statement.setString(2, Instant.now().toString());
                statement.setString(3, id);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Resolved input was concurrently changed: " + id);
                }
            }
            return requireFound(id);
        });
    }

    public synchronized CancelRequestResult requestCancel(String id) throws SQLException {
        return inTransaction(() -> {
            Optional<RuntimeExecution> found = find(id);
            if (found.isEmpty()) {
                return CancelRequestResult.NOT_FOUND;
            }
            RuntimeExecution current = found.get();
            if (current.terminal()) {
                return CancelRequestResult.ALREADY_TERMINAL;
            }
            if (current.cancelRequestedAt() != null) {
                return CancelRequestResult.ALREADY_REQUESTED;
            }
            Instant now = Instant.now();
            if (current.status() == ExecutionStatus.ENQUEUED) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE runtime_executions
                        SET status = 'canceled', outcome = 'canceled', cancel_requested_at = ?,
                            finished_at = ?, updated_at = ?
                        WHERE id = ? AND status = 'enqueued'
                        """)) {
                    statement.setString(1, now.toString());
                    statement.setString(2, now.toString());
                    statement.setString(3, now.toString());
                    statement.setString(4, id);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("Execution state changed while canceling: " + id);
                    }
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE runtime_executions
                        SET cancel_requested_at = ?, updated_at = ?
                        WHERE id = ? AND status = 'running' AND cancel_requested_at IS NULL
                        """)) {
                    statement.setString(1, now.toString());
                    statement.setString(2, now.toString());
                    statement.setString(3, id);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("Execution state changed while canceling: " + id);
                    }
                }
            }
            return CancelRequestResult.REQUESTED;
        });
    }

    public synchronized RuntimeExecution complete(
            String id, ExecutionOutcome requestedOutcome, String result, String error) throws SQLException {
        if (requestedOutcome == null) {
            throw new IllegalArgumentException("Execution outcome must not be null");
        }
        return inTransaction(() -> {
            RuntimeExecution current = requireFound(id);
            if (current.terminal()) {
                if (current.cancelRequestedAt() != null && current.outcome() == ExecutionOutcome.CANCELED) {
                    return current;
                }
                if (current.outcome() == requestedOutcome
                        && java.util.Objects.equals(current.result(), result)
                        && java.util.Objects.equals(current.error(), error)) {
                    return current;
                }
                throw new IllegalStateException("Conflicting terminal completion for execution: " + id);
            }
            if (current.status() != ExecutionStatus.RUNNING) {
                throw new IllegalStateException("Only a running execution can complete: " + id);
            }
            ExecutionOutcome outcome = current.cancelRequestedAt() == null
                    ? requestedOutcome : ExecutionOutcome.CANCELED;
            ExecutionStatus status = switch (outcome) {
                case SUCCEEDED, PARTIAL -> ExecutionStatus.COMPLETED;
                case FAILED, REJECTED -> ExecutionStatus.FAILED;
                case CANCELED -> ExecutionStatus.CANCELED;
            };
            Instant now = Instant.now();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE runtime_executions
                    SET status = ?, outcome = ?, result = ?, error = ?, finished_at = ?, updated_at = ?
                    WHERE id = ? AND status = 'running'
                    """)) {
                statement.setString(1, status.databaseValue());
                statement.setString(2, outcome.databaseValue());
                statement.setString(3, result);
                statement.setString(4, error);
                statement.setString(5, now.toString());
                statement.setString(6, now.toString());
                statement.setString(7, id);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Execution state changed while completing: " + id);
                }
            }
            return requireFound(id);
        });
    }

    public synchronized RuntimeExecution interruptForRecovery(String id, String reason) throws SQLException {
        requireText(reason, "reason");
        return inTransaction(() -> {
            RuntimeExecution current = requireFound(id);
            if (current.terminal()) {
                return current;
            }
            if (current.status() != ExecutionStatus.RUNNING) {
                throw new IllegalStateException("Only a running execution can be interrupted: " + id);
            }
            Instant now = Instant.now();
            if (current.cancelRequestedAt() != null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE runtime_executions
                        SET status = 'canceled', outcome = 'canceled', interruption_reason = ?,
                            finished_at = ?, updated_at = ?
                        WHERE id = ? AND status = 'running'
                        """)) {
                    statement.setString(1, reason);
                    statement.setString(2, now.toString());
                    statement.setString(3, now.toString());
                    statement.setString(4, id);
                    statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE runtime_executions
                        SET status = 'enqueued', started_at = NULL, interruption_reason = ?, updated_at = ?
                        WHERE id = ? AND status = 'running'
                        """)) {
                    statement.setString(1, reason);
                    statement.setString(2, now.toString());
                    statement.setString(3, id);
                    statement.executeUpdate();
                }
            }
            return requireFound(id);
        });
    }

    public synchronized int recoverStaleRunning(Path workspace) throws SQLException {
        String normalizedWorkspace = normalizeWorkspace(workspace);
        return inTransaction(() -> {
            Instant now = Instant.now();
            int changed;
            try (PreparedStatement cancel = connection.prepareStatement("""
                    UPDATE runtime_executions
                    SET status = 'canceled', outcome = 'canceled', finished_at = ?, updated_at = ?,
                        interruption_reason = 'recovered_cancel_request'
                    WHERE workspace = ? AND status = 'running' AND legacy_unbound = 0
                      AND cancel_requested_at IS NOT NULL
                    """)) {
                cancel.setString(1, now.toString());
                cancel.setString(2, now.toString());
                cancel.setString(3, normalizedWorkspace);
                changed = cancel.executeUpdate();
            }
            try (PreparedStatement requeue = connection.prepareStatement("""
                    UPDATE runtime_executions
                    SET status = 'enqueued', started_at = NULL, updated_at = ?,
                        interruption_reason = 'stale_running_recovery'
                    WHERE workspace = ? AND status = 'running' AND legacy_unbound = 0
                    """)) {
                requeue.setString(1, now.toString());
                requeue.setString(2, normalizedWorkspace);
                changed += requeue.executeUpdate();
            }
            return changed;
        });
    }

    private void migrate() throws SQLException {
        inTransaction(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS runtime_schema_migrations (
                            version INTEGER PRIMARY KEY,
                            applied_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS runtime_executions (
                            id TEXT PRIMARY KEY,
                            workspace TEXT NOT NULL,
                            session_id TEXT,
                            ordinal INTEGER,
                            status TEXT NOT NULL CHECK(status IN ('enqueued','running','completed','failed','canceled')),
                            submitted_input TEXT NOT NULL,
                            resolved_task_input TEXT,
                            explicit_mode TEXT,
                            selected_mode TEXT,
                            routing_source TEXT,
                            outcome TEXT,
                            result TEXT,
                            error TEXT,
                            attempt INTEGER NOT NULL DEFAULT 0,
                            legacy_unbound INTEGER NOT NULL DEFAULT 0 CHECK(legacy_unbound IN (0,1)),
                            cancel_requested_at TEXT,
                            interruption_reason TEXT,
                            created_at TEXT NOT NULL,
                            started_at TEXT,
                            finished_at TEXT,
                            updated_at TEXT NOT NULL,
                            UNIQUE(session_id, ordinal),
                            CHECK((legacy_unbound = 1 AND session_id IS NULL AND ordinal IS NULL)
                               OR (legacy_unbound = 0 AND session_id IS NOT NULL AND ordinal IS NOT NULL))
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_runtime_execution_queue
                        ON runtime_executions(workspace, status, created_at, ordinal)
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_runtime_execution_running_workspace
                        ON runtime_executions(workspace)
                        WHERE status = 'running' AND legacy_unbound = 0
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_runtime_execution_running_session
                        ON runtime_executions(session_id)
                        WHERE status = 'running' AND legacy_unbound = 0 AND session_id IS NOT NULL
                        """);
            }
            if (!migrationApplied(SCHEMA_VERSION)) {
                if (tableExists("runtime_tasks")) {
                    migrateLegacyRows();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO runtime_schema_migrations(version, applied_at) VALUES(?, ?)")) {
                    statement.setInt(1, SCHEMA_VERSION);
                    statement.setString(2, Instant.now().toString());
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    private void migrateLegacyRows() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM runtime_tasks")) {
            Set<String> columns = columns(rows.getMetaData());
            while (rows.next()) {
                String id = rows.getString("id");
                ExecutionStatus status = ExecutionStatus.fromDatabase(rows.getString("status"));
                String createdAt = nullable(rows, columns, "created_at");
                if (createdAt == null) {
                    throw new IllegalStateException("Legacy execution has no created_at: " + id);
                }
                String updatedAt = firstNonNull(
                        nullable(rows, columns, "updated_at"),
                        nullable(rows, columns, "finished_at"),
                        nullable(rows, columns, "started_at"),
                        createdAt);
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT OR IGNORE INTO runtime_executions(
                            id, workspace, session_id, ordinal, status, submitted_input, result, error,
                            attempt, legacy_unbound, created_at, started_at, finished_at, updated_at)
                        VALUES(?, '<legacy-unbound>', NULL, NULL, ?, ?, ?, ?, 0, 1, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, id);
                    insert.setString(2, status.databaseValue());
                    insert.setString(3, rows.getString("prompt"));
                    insert.setString(4, nullable(rows, columns, "result"));
                    insert.setString(5, nullable(rows, columns, "error"));
                    insert.setString(6, createdAt);
                    insert.setString(7, nullable(rows, columns, "started_at"));
                    insert.setString(8, nullable(rows, columns, "finished_at"));
                    insert.setString(9, updatedAt);
                    insert.executeUpdate();
                }
            }
        }
    }

    private boolean migrationApplied(int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM runtime_schema_migrations WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private RuntimeExecution requireFound(String id) throws SQLException {
        return find(id).orElseThrow(() -> new IllegalStateException("Unknown execution: " + id));
    }

    private RuntimeExecution map(ResultSet rows) throws SQLException {
        return new RuntimeExecution(
                rows.getString("id"),
                rows.getString("workspace"),
                rows.getString("session_id"),
                nullableLong(rows, "ordinal"),
                ExecutionStatus.fromDatabase(rows.getString("status")),
                rows.getString("submitted_input"),
                rows.getString("resolved_task_input"),
                decodeMode(rows.getString("explicit_mode")),
                decodeMode(rows.getString("selected_mode")),
                decodeRoutingSource(rows.getString("routing_source")),
                ExecutionOutcome.fromDatabase(rows.getString("outcome")),
                rows.getString("result"),
                rows.getString("error"),
                rows.getInt("attempt"),
                rows.getInt("legacy_unbound") == 1,
                instant(rows.getString("cancel_requested_at")),
                rows.getString("interruption_reason"),
                instant(rows.getString("created_at")),
                instant(rows.getString("started_at")),
                instant(rows.getString("finished_at")),
                instant(rows.getString("updated_at")));
    }

    private <T> T inTransaction(SqlSupplier<T> operation) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException("Nested store transaction is not supported");
        }
        try (Statement begin = connection.createStatement()) {
            begin.execute("BEGIN IMMEDIATE");
        }
        try {
            T result = operation.get();
            try (Statement commit = connection.createStatement()) {
                commit.execute("COMMIT");
            }
            return result;
        } catch (SQLException | RuntimeException exception) {
            try (Statement rollback = connection.createStatement()) {
                rollback.execute("ROLLBACK");
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        }
    }

    private static Set<String> columns(ResultSetMetaData metadata) throws SQLException {
        Set<String> names = new HashSet<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            names.add(metadata.getColumnName(index).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static String nullable(ResultSet rows, Set<String> columns, String name) throws SQLException {
        return columns.contains(name) ? rows.getString(name) : null;
    }

    private static Long nullableLong(ResultSet rows, String name) throws SQLException {
        long value = rows.getLong(name);
        return rows.wasNull() ? null : value;
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String normalizeWorkspace(Path workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace must not be null");
        }
        return workspace.toAbsolutePath().normalize().toString();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String encode(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    private static ExecutionMode decodeMode(String value) {
        return value == null ? null : ExecutionMode.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static RoutingSource decodeRoutingSource(String value) {
        return value == null ? null : RoutingSource.valueOf(value.toUpperCase(Locale.ROOT));
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
