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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RuntimeExecutionStore implements AutoCloseable {
    private static final int SCHEMA_VERSION = 2;
    private final Connection connection;
    private final WorkspaceIdentityResolver identityResolver = new WorkspaceIdentityResolver();

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
                statement.execute("PRAGMA foreign_keys = ON");
            }
            if (tableExists("runtime_schema_migrations")) {
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COALESCE(MAX(version),0) FROM runtime_schema_migrations")) {
                    if (rows.next() && rows.getInt(1) > SCHEMA_VERSION) throw new SQLException("Execution database schema is newer than this runtime");
                }
            }
            if ((tableExists("runtime_executions") || tableExists("runtime_tasks"))
                    && (!tableExists("runtime_schema_migrations") || !migrationApplied(SCHEMA_VERSION))) {
                Path backup = absolute.resolveSibling(absolute.getFileName() + ".pre-v2-" + UUID.randomUUID() + ".bak");
                try (var statement = connection.prepareStatement("VACUUM INTO ?")) {
                    statement.setString(1, backup.toString()); statement.execute();
                }
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
        WorkspaceIdentity identity = resolveWorkspace(workspace);
        return inTransaction(() -> {
            ensureWorkspace(identity);
            ensureSessionBinding(sessionId, identity.key());
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
                        id, workspace, workspace_key, session_id, ordinal, status, submitted_input, explicit_mode,
                        attempt, legacy_unbound, created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, 'enqueued', ?, ?, 0, 0, ?, ?)
                    """)) {
                statement.setString(1, id);
                statement.setString(2, identity.canonicalRoot().toString());
                statement.setString(3, identity.key().value());
                statement.setString(4, sessionId);
                statement.setLong(5, ordinal);
                statement.setString(6, submittedInput);
                statement.setString(7, encode(explicitMode));
                statement.setString(8, now.toString());
                statement.setString(9, now.toString());
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
        WorkspaceIdentity identity = resolveWorkspace(workspace);
        return inTransaction(() -> {
            ensureWorkspace(identity);
            ensureSessionBinding(sessionId, identity.key());
            Optional<RuntimeExecution> existing = find(executionId);
            if (existing.isPresent()) {
                RuntimeExecution value = existing.orElseThrow();
                if (!identity.key().equals(value.workspaceKey())
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
                        id, workspace, workspace_key, session_id, ordinal, status, submitted_input, explicit_mode,
                        attempt, legacy_unbound, created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, 'enqueued', ?, 'plan', 0, 0, ?, ?)
                    """)) {
                statement.setString(1, executionId);
                statement.setString(2, identity.canonicalRoot().toString());
                statement.setString(3, identity.key().value());
                statement.setString(4, sessionId);
                statement.setLong(5, ordinal);
                statement.setString(6, submittedInput);
                statement.setString(7, now.toString());
                statement.setString(8, now.toString());
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
        WorkspaceKey workspaceKey = resolveWorkspace(workspace).key();
        List<RuntimeExecution> executions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM runtime_executions
                WHERE workspace_key = ? AND legacy_unbound = 0
                ORDER BY created_at DESC, ordinal DESC, id DESC
                LIMIT ?
                """)) {
            statement.setString(1, workspaceKey.value());
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
        WorkspaceKey workspaceKey = resolveWorkspace(workspace).key();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM runtime_executions
                WHERE workspace_key = ? AND legacy_unbound = 0 AND status = 'running'
                ORDER BY started_at, id
                LIMIT 1
                """)) {
            statement.setString(1, workspaceKey.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        }
    }

    synchronized Optional<RuntimeExecution> claimNext(Path workspace) throws SQLException {
        return claimNext(workspace, "owner_" + UUID.randomUUID().toString().replace("-", ""));
    }

    synchronized Optional<RuntimeExecution> claimNext(Path workspace, String ownerId) throws SQLException {
        return claimNext(workspace, ownerId, null);
    }

    public synchronized Optional<RuntimeExecution> claimCandidate(
            WorkspaceOwnerLease owner, String candidateId) throws SQLException {
        verifyOwner(owner);
        return claimNext(owner.identity().canonicalRoot(), owner.ownerId(), candidateId);
    }

    private Optional<RuntimeExecution> claimNext(Path workspace, String ownerId, String candidateId) throws SQLException {
        requireText(ownerId, "ownerId");
        WorkspaceKey workspaceKey = resolveWorkspace(workspace).key();
        return inTransaction(() -> {
            String id = null;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT candidate.id
                    FROM runtime_executions candidate
                    WHERE candidate.workspace_key = ?
                      AND candidate.status = 'enqueued'
                      AND candidate.legacy_unbound = 0
                      AND (? IS NULL OR candidate.id = ?)
                      AND NOT EXISTS (
                          SELECT 1 FROM runtime_executions running
                          WHERE running.workspace_key = candidate.workspace_key
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
                statement.setString(1, workspaceKey.value());
                statement.setString(2, candidateId);
                statement.setString(3, candidateId);
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
                        updated_at = ?, attempt = attempt + 1, owner_id = ?
                    WHERE id = ? AND status = 'enqueued'
                    """)) {
                statement.setString(1, now.toString());
                statement.setString(2, now.toString());
                statement.setString(3, ownerId);
                statement.setString(4, id);
                if (statement.executeUpdate() != 1) {
                    return Optional.empty();
                }
            }
            return Optional.of(requireFound(id));
        });
    }

    public synchronized List<RuntimeExecution> workspaceExecutions(WorkspaceKey key) throws SQLException {
        List<RuntimeExecution> values = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT * FROM runtime_executions WHERE workspace_key=? AND legacy_unbound=0 ORDER BY created_at, ordinal, id")) {
            statement.setString(1, key.value());
            try (var rows = statement.executeQuery()) { while (rows.next()) values.add(map(rows)); }
        }
        return List.copyOf(values);
    }

    synchronized Optional<RuntimeExecution> nextQueuedExecution(WorkspaceKey key) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT * FROM runtime_executions
                WHERE workspace_key=? AND legacy_unbound=0 AND status='enqueued'
                ORDER BY created_at, ordinal, id LIMIT 1
                """)) {
            statement.setString(1, key.value());
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        }
    }

    /** Migration diagnostics for trusted hosts; unresolved rows remain ineligible for claim. */
    public synchronized List<RuntimeExecution> unresolvedWorkspaceExecutions(int limit) throws SQLException {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        List<RuntimeExecution> values = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT * FROM runtime_executions
                WHERE workspace_key IS NULL AND legacy_unbound=0
                ORDER BY created_at, ordinal, id LIMIT ?
                """)) {
            statement.setInt(1, limit);
            try (var rows = statement.executeQuery()) { while (rows.next()) values.add(map(rows)); }
        }
        return List.copyOf(values);
    }

    public synchronized void bindSession(WorkspaceIdentity identity, String sessionId) throws SQLException {
        requireText(sessionId, "sessionId");
        inTransaction(() -> { ensureWorkspace(identity); ensureSessionBinding(sessionId, identity.key()); return null; });
    }

    public synchronized Optional<WorkspaceKey> sessionWorkspace(String sessionId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT workspace_key FROM runtime_session_bindings WHERE session_id=?")) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new WorkspaceKey(rows.getString(1))) : Optional.empty();
            }
        }
    }

    public synchronized void registerWorkspace(WorkspaceOwnerLease owner) throws SQLException {
        registerWorkspace(owner, owner.identity().canonicalRoot());
    }

    public synchronized void registerWorkspace(WorkspaceOwnerLease owner, Path requestedRoot) throws SQLException {
        verifyOwner(owner);
        if (!owner.identity().key().equals(resolveWorkspace(requestedRoot).key())) {
            throw new IllegalArgumentException("Registration path does not match workspace owner");
        }
        inTransaction(() -> {
            ensureWorkspace(owner.identity());
            migrateExistingWorkspaceIdentities(owner.identity(), requestedRoot.toAbsolutePath().normalize().toString());
            return null;
        });
    }

    public synchronized RuntimeExecution claimForReconciliation(WorkspaceOwnerLease owner, String id) throws SQLException {
        verifyOwner(owner);
        return inTransaction(() -> {
            RuntimeExecution current = requireFound(id);
            if (!owner.identity().key().equals(current.workspaceKey())) throw new IllegalStateException("Recovery workspace mismatch");
            if (current.terminal()) return current;
            try (var statement = connection.prepareStatement("""
                    UPDATE runtime_executions SET status='running', owner_id=?, attempt=attempt+1
                    WHERE id=? AND status IN ('running','enqueued')
                    """)) {
                statement.setString(1, owner.ownerId()); statement.setString(2, id);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Recovery state changed");
            }
            return requireFound(id);
        });
    }

    public synchronized int recoverStaleRunning(WorkspaceOwnerLease owner) throws SQLException {
        verifyOwner(owner);
        return recoverStaleRunning(owner.identity().canonicalRoot());
    }

    private static void verifyOwner(WorkspaceOwnerLease owner) throws SQLException {
        try { owner.verifyIdentity(); }
        catch (IOException e) { throw new SQLException("Workspace ownership verification failed", e); }
    }

    public synchronized RuntimeExecution commitRoutingDecision(
            ExecutionClaim claim, ExecutionMode selectedMode, RoutingSource routingSource) throws SQLException {
        if (selectedMode == null || routingSource == null) {
            throw new IllegalArgumentException("Routing decision must be complete");
        }
        String id = claim.executionId();
        return inTransaction(() -> {
            RuntimeExecution current = requireFound(id);
            requireClaim(current, claim);
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
                    WHERE id = ? AND status = 'running' AND owner_id = ? AND attempt = ?
                      AND selected_mode IS NULL
                    """)) {
                statement.setString(1, encode(selectedMode));
                statement.setString(2, encode(routingSource));
                statement.setString(3, Instant.now().toString());
                statement.setString(4, id);
                statement.setString(5, claim.ownerId());
                statement.setInt(6, claim.attempt());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Routing decision was concurrently changed: " + id);
                }
            }
            return requireFound(id);
        });
    }

    public synchronized RuntimeExecution commitResolvedInput(
            ExecutionClaim claim, String resolvedTaskInput) throws SQLException {
        requireText(resolvedTaskInput, "resolvedTaskInput");
        String id = claim.executionId();
        return inTransaction(() -> {
            RuntimeExecution current = requireFound(id);
            requireClaim(current, claim);
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
                    WHERE id = ? AND status = 'running' AND owner_id = ? AND attempt = ?
                      AND resolved_task_input IS NULL
                    """)) {
                statement.setString(1, resolvedTaskInput);
                statement.setString(2, Instant.now().toString());
                statement.setString(3, id);
                statement.setString(4, claim.ownerId());
                statement.setInt(5, claim.attempt());
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
            ExecutionClaim claim, ExecutionOutcome requestedOutcome, String result, String error) throws SQLException {
        return complete(claim, requestedOutcome, result, error, false, (execution, outcome) -> { });
    }

    synchronized RuntimeExecution completeWithEnvelope(ExecutionClaim claim, ExecutionOutcome outcome,
            String result, String error, BeforeCompletion before) throws SQLException {
        return complete(claim, outcome, result, error, false, before);
    }

    synchronized RuntimeExecution completeReconciliation(WorkspaceOwnerLease owner, ExecutionClaim claim,
            ExecutionOutcome outcome, String result, String error) throws SQLException {
        verifyOwner(owner);
        if (!owner.identity().key().equals(requireFound(claim.executionId()).workspaceKey())) {
            throw new IllegalStateException("Recovery workspace mismatch");
        }
        return complete(claim, outcome, result, error, true, (execution, effectiveOutcome) -> { });
    }

    private RuntimeExecution complete(ExecutionClaim claim, ExecutionOutcome requestedOutcome, String result,
            String error, boolean authoritativeEnvelope, BeforeCompletion before) throws SQLException {
        if (requestedOutcome == null) {
            throw new IllegalArgumentException("Execution outcome must not be null");
        }
        String id = claim.executionId();
        return inTransaction(() -> {
            RuntimeExecution current = requireFound(id);
            requireClaim(current, claim);
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
            ExecutionOutcome outcome = authoritativeEnvelope || current.cancelRequestedAt() == null
                    ? requestedOutcome : ExecutionOutcome.CANCELED;
            ExecutionStatus status = switch (outcome) {
                case SUCCEEDED, PARTIAL -> ExecutionStatus.COMPLETED;
                case FAILED, REJECTED -> ExecutionStatus.FAILED;
                case CANCELED -> ExecutionStatus.CANCELED;
            };
            Instant now = Instant.now();
            before.append(current, outcome);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE runtime_executions
                    SET status = ?, outcome = ?, result = ?, error = ?, finished_at = ?, updated_at = ?
                    WHERE id = ? AND status = 'running' AND owner_id = ? AND attempt = ?
                    """)) {
                statement.setString(1, status.databaseValue());
                statement.setString(2, outcome.databaseValue());
                statement.setString(3, result);
                statement.setString(4, error);
                statement.setString(5, now.toString());
                statement.setString(6, now.toString());
                statement.setString(7, id);
                statement.setString(8, claim.ownerId());
                statement.setInt(9, claim.attempt());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Execution state changed while completing: " + id);
                }
            }
            return requireFound(id);
        });
    }

    @FunctionalInterface interface BeforeCompletion {
        void append(RuntimeExecution execution, ExecutionOutcome effectiveOutcome) throws SQLException;
    }

    public synchronized RuntimeExecution interruptForRecovery(ExecutionClaim claim, String reason) throws SQLException {
        requireText(reason, "reason");
        String id = claim.executionId();
        return inTransaction(() -> {
            RuntimeExecution current = requireFound(id);
            requireClaim(current, claim);
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
                            finished_at = ?, updated_at = ?,
                            selected_mode = COALESCE(selected_mode, explicit_mode, 'react'),
                            routing_source = COALESCE(routing_source, CASE WHEN explicit_mode IS NULL THEN 'auto_fallback' ELSE 'explicit' END)
                        WHERE id = ? AND status = 'running' AND owner_id = ? AND attempt = ?
                        """)) {
                    statement.setString(1, reason);
                    statement.setString(2, now.toString());
                    statement.setString(3, now.toString());
                    statement.setString(4, id);
                    statement.setString(5, claim.ownerId());
                    statement.setInt(6, claim.attempt());
                    statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE runtime_executions
                        SET status = 'enqueued', started_at = NULL, owner_id = NULL,
                            interruption_reason = ?, updated_at = ?
                        WHERE id = ? AND status = 'running' AND owner_id = ? AND attempt = ?
                        """)) {
                    statement.setString(1, reason);
                    statement.setString(2, now.toString());
                    statement.setString(3, id);
                    statement.setString(4, claim.ownerId());
                    statement.setInt(5, claim.attempt());
                    statement.executeUpdate();
                }
            }
            return requireFound(id);
        });
    }

    private synchronized int recoverStaleRunning(Path workspace) throws SQLException {
        WorkspaceKey workspaceKey = resolveWorkspace(workspace).key();
        return inTransaction(() -> {
            Instant now = Instant.now();
            int changed;
            try (PreparedStatement cancel = connection.prepareStatement("""
                    UPDATE runtime_executions
                    SET status = 'canceled', outcome = 'canceled', finished_at = ?, updated_at = ?,
                        interruption_reason = 'recovered_cancel_request',
                        selected_mode = COALESCE(selected_mode, explicit_mode, 'react'),
                        routing_source = COALESCE(routing_source, CASE WHEN explicit_mode IS NULL THEN 'auto_fallback' ELSE 'explicit' END)
                    WHERE workspace_key = ? AND status = 'running' AND legacy_unbound = 0
                      AND cancel_requested_at IS NOT NULL
                    """)) {
                cancel.setString(1, now.toString());
                cancel.setString(2, now.toString());
                cancel.setString(3, workspaceKey.value());
                changed = cancel.executeUpdate();
            }
            try (PreparedStatement requeue = connection.prepareStatement("""
                    UPDATE runtime_executions
                    SET status = 'enqueued', started_at = NULL, owner_id = NULL, updated_at = ?,
                        interruption_reason = 'stale_running_recovery'
                    WHERE workspace_key = ? AND status = 'running' AND legacy_unbound = 0
                    """)) {
                requeue.setString(1, now.toString());
                requeue.setString(2, workspaceKey.value());
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
                            workspace_key TEXT REFERENCES runtime_workspaces(workspace_key),
                            owner_id TEXT,
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
                        CREATE TABLE IF NOT EXISTS runtime_workspaces (
                            workspace_key TEXT PRIMARY KEY,
                            canonical_root TEXT NOT NULL UNIQUE,
                            identity_version INTEGER NOT NULL CHECK(identity_version = 1),
                            created_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS runtime_session_bindings (
                            session_id TEXT PRIMARY KEY,
                            workspace_key TEXT NOT NULL REFERENCES runtime_workspaces(workspace_key),
                            bound_at TEXT NOT NULL
                        )
                        """);
            }
            if (!columnExists("runtime_executions", "workspace_key")) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE runtime_executions ADD COLUMN workspace_key TEXT REFERENCES runtime_workspaces(workspace_key)");
                }
            }
            if (!columnExists("runtime_executions", "owner_id")) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE runtime_executions ADD COLUMN owner_id TEXT");
                }
            }
            if (!migrationApplied(1)) {
                if (tableExists("runtime_tasks")) {
                    migrateLegacyRows();
                }
                recordMigration(1);
            }
            if (!migrationApplied(SCHEMA_VERSION)) {
                migrateExistingWorkspaceIdentities();
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP INDEX IF EXISTS uq_runtime_execution_running_workspace");
                    statement.execute("DROP INDEX IF EXISTS idx_runtime_execution_queue");
                    statement.execute("""
                            CREATE INDEX idx_runtime_execution_queue
                            ON runtime_executions(workspace_key, status, created_at, ordinal)
                            """);
                    statement.execute("""
                            CREATE UNIQUE INDEX uq_runtime_execution_running_workspace
                            ON runtime_executions(workspace_key)
                            WHERE status = 'running' AND legacy_unbound = 0 AND workspace_key IS NOT NULL
                            """);
                    statement.execute("""
                            CREATE UNIQUE INDEX IF NOT EXISTS uq_runtime_execution_running_session
                            ON runtime_executions(session_id)
                            WHERE status = 'running' AND legacy_unbound = 0 AND session_id IS NOT NULL
                            """);
                }
                recordMigration(SCHEMA_VERSION);
            }
            return null;
        });
    }

    private void migrateExistingWorkspaceIdentities() throws SQLException {
        migrateExistingWorkspaceIdentities(null, null);
    }

    private void migrateExistingWorkspaceIdentities(WorkspaceIdentity registeredIdentity, String requestedRoot) throws SQLException {
        WorkspaceKey registeredWorkspace = registeredIdentity == null ? null : registeredIdentity.key();
        Map<String, WorkspaceIdentity> identitiesByRoot = new HashMap<>();
        Map<String, Set<String>> sessionKeys = new HashMap<>();
        Set<String> unresolvedSessions = new HashSet<>();
        List<String[]> existing = new ArrayList<>();
        String query = registeredIdentity == null ? """
                     SELECT workspace, session_id FROM runtime_executions
                     WHERE legacy_unbound = 0 AND session_id IS NOT NULL
                     """ : """
                     SELECT workspace, session_id FROM runtime_executions
                     WHERE legacy_unbound=0 AND session_id IN (
                         SELECT session_id FROM runtime_executions
                         WHERE legacy_unbound=0 AND workspace_key IS NULL
                           AND (workspace=? OR workspace=?)
                     )
                     """;
        try (var statement = connection.prepareStatement(query)) {
            if (registeredIdentity != null) {
                statement.setString(1, registeredIdentity.canonicalRoot().toString());
                statement.setString(2, requestedRoot);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) existing.add(new String[]{rows.getString(1), rows.getString(2)});
            }
        }
        for (String[] row : existing) {
            String oldRoot = row[0];
            WorkspaceIdentity identity = identitiesByRoot.get(oldRoot);
            if (identity == null) {
                try {
                    identity = identityResolver.resolve(Path.of(oldRoot));
                    identitiesByRoot.put(oldRoot, identity);
                    if (registeredWorkspace == null || registeredWorkspace.equals(identity.key())) ensureWorkspace(identity);
                } catch (IOException | RuntimeException unresolved) {
                    unresolvedSessions.add(row[1]);
                    continue;
                }
            }
            sessionKeys.computeIfAbsent(row[1], ignored -> new HashSet<>()).add(identity.key().value());
        }
        for (Map.Entry<String, Set<String>> entry : sessionKeys.entrySet()) {
            if (entry.getValue().size() != 1) {
                throw new SQLException("Session has conflicting workspace roots: " + entry.getKey());
            }
            if (unresolvedSessions.contains(entry.getKey())) {
                continue;
            }
            String key = entry.getValue().iterator().next();
            if (registeredWorkspace != null && !registeredWorkspace.value().equals(key)) continue;
            try (PreparedStatement binding = connection.prepareStatement("""
                    INSERT OR IGNORE INTO runtime_session_bindings(session_id, workspace_key, bound_at)
                    VALUES(?, ?, ?)
                    """)) {
                binding.setString(1, entry.getKey());
                binding.setString(2, key);
                binding.setString(3, Instant.now().toString());
                binding.executeUpdate();
            }
            try (PreparedStatement verify = connection.prepareStatement(
                    "SELECT workspace_key FROM runtime_session_bindings WHERE session_id = ?")) {
                verify.setString(1, entry.getKey());
                try (ResultSet rows = verify.executeQuery()) {
                    if (rows.next() && key.equals(rows.getString(1))) {
                        for (Map.Entry<String, WorkspaceIdentity> root : identitiesByRoot.entrySet()) {
                            if (!key.equals(root.getValue().key().value())) {
                                continue;
                            }
                            try (PreparedStatement update = connection.prepareStatement("""
                                    UPDATE runtime_executions SET workspace_key = ?
                                    WHERE session_id = ? AND workspace = ? AND legacy_unbound = 0
                                    """)) {
                                update.setString(1, key);
                                update.setString(2, entry.getKey());
                                update.setString(3, root.getKey());
                                update.executeUpdate();
                            }
                        }
                    }
                }
            }
        }
    }

    private void ensureWorkspace(WorkspaceIdentity identity) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO runtime_workspaces(workspace_key, canonical_root, identity_version, created_at)
                VALUES(?, ?, 1, ?)
                """)) {
            insert.setString(1, identity.key().value());
            insert.setString(2, identity.canonicalRoot().toString());
            insert.setString(3, Instant.now().toString());
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT canonical_root FROM runtime_workspaces WHERE workspace_key = ?")) {
            query.setString(1, identity.key().value());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !identity.canonicalRoot().toString().equals(rows.getString(1))) {
                    throw new SQLException("Workspace identity key collision: " + identity.key().value());
                }
            }
        }
    }

    private void ensureSessionBinding(String sessionId, WorkspaceKey workspaceKey) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO runtime_session_bindings(session_id, workspace_key, bound_at)
                VALUES(?, ?, ?)
                """)) {
            insert.setString(1, sessionId);
            insert.setString(2, workspaceKey.value());
            insert.setString(3, Instant.now().toString());
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT workspace_key FROM runtime_session_bindings WHERE session_id = ?")) {
            query.setString(1, sessionId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !workspaceKey.value().equals(rows.getString(1))) {
                    throw new IllegalStateException("Session is already bound to another workspace: " + sessionId);
                }
            }
        }
    }

    private WorkspaceIdentity resolveWorkspace(Path workspace) throws SQLException {
        try {
            return identityResolver.resolve(workspace);
        } catch (IOException failure) {
            throw new SQLException("Workspace identity cannot be resolved: " + workspace, failure);
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (column.equalsIgnoreCase(rows.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private void recordMigration(int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO runtime_schema_migrations(version, applied_at) VALUES(?, ?)")) {
            statement.setInt(1, version);
            statement.setString(2, Instant.now().toString());
            statement.executeUpdate();
        }
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

    private static void requireClaim(RuntimeExecution execution, ExecutionClaim claim) {
        if (claim == null || !execution.id().equals(claim.executionId())
                || !java.util.Objects.equals(execution.ownerId(), claim.ownerId())
                || execution.attempt() != claim.attempt()) {
            throw new IllegalStateException("Execution claim is stale: " + execution.id());
        }
    }

    private RuntimeExecution map(ResultSet rows) throws SQLException {
        return new RuntimeExecution(
                rows.getString("id"),
                rows.getString("workspace"),
                nullableWorkspaceKey(rows.getString("workspace_key")),
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
                rows.getString("owner_id"),
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

    private static WorkspaceKey nullableWorkspaceKey(String value) {
        return value == null ? null : new WorkspaceKey(value);
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
