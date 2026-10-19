package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.RoutingSource;
import com.codeagent.history.SessionProjection;

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
    private static final int SCHEMA_VERSION = 4;
    private static final String READY_CANDIDATE_QUERY = """
                    SELECT candidate.*
                    FROM runtime_executions candidate
                    WHERE candidate.workspace_key = ?
                      AND candidate.status = 'enqueued'
                      AND candidate.dispatch_state = 'ready'
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
                            AND (head.status = 'running' OR (head.status = 'enqueued' AND head.dispatch_state = 'ready'))
                      )
                    ORDER BY candidate.created_at, candidate.ordinal, candidate.id
                    LIMIT 1
                    """;
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
                Path backup = absolute.resolveSibling(absolute.getFileName() + ".pre-v" + SCHEMA_VERSION + "-" + UUID.randomUUID() + ".bak");
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

    /** Exact session recovery view, independent of UI's 100-row workspace page. */
    public synchronized List<RuntimeExecution> listRecoveryState(Path workspace, String sessionId) throws SQLException {
        WorkspaceKey key=resolveWorkspace(workspace).key();
        var result=new ArrayList<RuntimeExecution>();
        try(var query=connection.prepareStatement(
                "SELECT * FROM runtime_executions WHERE workspace_key=? AND session_id=? AND legacy_unbound=0 AND status IN ('enqueued','running') ORDER BY ordinal,id")){
            query.setString(1,key.value());query.setString(2,sessionId);
            try(var rows=query.executeQuery()){
                while(rows.next()){
                    if(result.size()==1000)throw new SQLException("Recovery execution limit exceeded");
                    result.add(map(rows));
                }
            }
        }
        return List.copyOf(result);
    }

    public synchronized boolean hasAwaitingTask(Path workspace, String sessionId) throws SQLException {
        requireText(sessionId, "sessionId");
        WorkspaceKey key = resolveWorkspace(workspace).key();
        try (var query = connection.prepareStatement("""
                SELECT 1 FROM runtime_executions WHERE workspace_key=? AND session_id=?
                  AND legacy_unbound=0 AND status='enqueued' AND dispatch_state='awaiting_input'
                  AND request_kind='task' LIMIT 1
                """)) {
            query.setString(1, key.value());
            query.setString(2, sessionId);
            try (var rows = query.executeQuery()) { return rows.next(); }
        }
    }

    public synchronized boolean hasActiveOrReadyExecution(String sessionId) throws SQLException {
        requireText(sessionId, "sessionId");
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM runtime_executions WHERE session_id=? AND legacy_unbound=0
                  AND (status='running' OR (status='enqueued' AND dispatch_state='ready')) LIMIT 1
                """)) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) { return rows.next(); }
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
            try (PreparedStatement statement = connection.prepareStatement(READY_CANDIDATE_QUERY)) {
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
                    WHERE id = ? AND status = 'enqueued' AND dispatch_state = 'ready'
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
        try (var statement = connection.prepareStatement(READY_CANDIDATE_QUERY)) {
            statement.setString(1,key.value()); statement.setString(2,null); statement.setString(3,null);
            try (var rows=statement.executeQuery()) { return rows.next() ? Optional.of(map(rows)) : Optional.empty(); }
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

    /** Reconciles a durable END without reserving the workspace's unique running slot. */
    synchronized RuntimeExecution completeReconciliation(WorkspaceOwnerLease owner, RuntimeExecution expected,
            SessionProjection.ExecutionEnvelope envelope) throws SQLException {
        verifyOwner(owner);
        return inTransaction(() -> {
            RuntimeExecution current = requireFound(expected.id());
            if (!owner.identity().key().equals(current.workspaceKey()) || current.legacyUnbound())
                throw new IllegalStateException("Recovery workspace mismatch");
            if (!current.equals(expected) || current.terminal())
                throw new IllegalStateException("Recovery execution snapshot is stale");
            if (envelope == null || !envelope.ended() || envelope.startSequence() < 1
                    || envelope.endSequence() <= envelope.startSequence()
                    || envelope.selectedMode() == null || envelope.outcome() == null || envelope.status() == null
                    || !current.id().equals(envelope.executionId())
                    || !java.util.Objects.equals(current.ordinal(), envelope.ordinal())
                    || !java.util.Objects.equals(encode(current.explicitMode()), envelope.explicitMode()))
                throw new IllegalArgumentException("Execution END identity conflicts with execution");
            ExecutionMode mode = decodeMode(envelope.selectedMode());
            ExecutionOutcome outcome = ExecutionOutcome.fromDatabase(envelope.outcome());
            ExecutionStatus status = statusFor(outcome);
            if (mode == null || !encode(mode).equals(envelope.selectedMode())
                    || (current.selectedMode() != null && current.selectedMode() != mode)
                    || (current.explicitMode() != null && current.explicitMode() != mode)
                    || !status.databaseValue().equals(envelope.status()))
                throw new IllegalArgumentException("Execution END terminal state conflicts with execution");
            RoutingSource source = current.routingSource() != null ? current.routingSource()
                    : current.explicitMode() == null ? RoutingSource.AUTO_FALLBACK : RoutingSource.EXPLICIT;
            String now = Instant.now().toString();
            try (var statement = connection.prepareStatement("""
                    UPDATE runtime_executions SET status=?, outcome=?, selected_mode=?, routing_source=?,
                        owner_id=?, attempt=attempt+1, finished_at=?, updated_at=?
                    WHERE id=? AND status=? AND attempt=?
                    """)) {
                statement.setString(1, status.databaseValue()); statement.setString(2, outcome.databaseValue());
                statement.setString(3, encode(mode)); statement.setString(4, encode(source));
                statement.setString(5, owner.ownerId()); statement.setString(6, now); statement.setString(7, now);
                statement.setString(8, current.id()); statement.setString(9, current.status().databaseValue());
                statement.setInt(10, current.attempt());
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Recovery state changed");
            }
            return requireFound(current.id());
        });
    }

    public synchronized int recoverStaleRunning(WorkspaceOwnerLease owner) throws SQLException {
        verifyOwner(owner);
        return recoverStaleRunning(owner.identity().canonicalRoot());
    }

    /** Called after terminal-envelope reconciliation while this workspace owner is exclusive. */
    public synchronized int freezeForUser(WorkspaceOwnerLease owner) throws SQLException {
        verifyOwner(owner);
        return inTransaction(() -> {
            String now = Instant.now().toString();
            try (var cancel = connection.prepareStatement("""
                    UPDATE runtime_executions SET status='canceled',outcome='canceled',finished_at=?,updated_at=?,
                        interruption_reason='recovered_cancel_request',
                        selected_mode=COALESCE(selected_mode,explicit_mode,'react'),
                        routing_source=COALESCE(routing_source,CASE WHEN explicit_mode IS NULL THEN 'auto_fallback' ELSE 'explicit' END)
                    WHERE workspace_key=? AND legacy_unbound=0 AND status IN ('running','enqueued') AND cancel_requested_at IS NOT NULL
                    """)) {
                cancel.setString(1,now); cancel.setString(2,now); cancel.setString(3,owner.identity().key().value());
                cancel.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    UPDATE runtime_executions SET dispatch_state='awaiting_input',
                        recovery_generation=recovery_generation+1, owner_id=NULL, started_at=NULL,
                        interruption_reason=CASE WHEN status='running' THEN 'stale_running_recovery' ELSE interruption_reason END,
                        status='enqueued', continuation_request_id=NULL, current_policy_input=NULL, updated_at=?
                    WHERE workspace_key=? AND legacy_unbound=0 AND status IN ('running','enqueued')
                    """)) {
                statement.setString(1,now); statement.setString(2,owner.identity().key().value());
                return statement.executeUpdate();
            }
        });
    }

    /** Defers only a current attempt whose host-side recovery preparation has not executed work. */
    public synchronized RuntimeExecution deferForUser(ExecutionClaim claim, String reason) throws SQLException {
        requireText(reason,"reason");
        return inTransaction(() -> {
            RuntimeExecution current=requireFound(claim.executionId());
            requireClaim(current,claim);
            if(current.status()!=ExecutionStatus.RUNNING || current.cancelRequestedAt()!=null)
                throw new IllegalStateException("Cannot defer inactive or canceled execution");
            try(var update=connection.prepareStatement("""
                    UPDATE runtime_executions SET status='enqueued',dispatch_state='awaiting_input',
                        recovery_generation=recovery_generation+1,owner_id=NULL,started_at=NULL,
                        continuation_request_id=NULL,current_policy_input=NULL,interruption_reason=?,updated_at=? WHERE id=?
                    """)) {
                update.setString(1,reason); update.setString(2,Instant.now().toString()); update.setString(3,current.id());
                update.executeUpdate();
            }
            return requireFound(current.id());
        });
    }

    /** Records only durable request metadata. Target stays held until the request finalizer succeeds. */
    public synchronized RuntimeExecution prepareContinuation(ExecutionClaim claim, String targetId, int generation) throws SQLException {
        return inTransaction(() -> {
            RuntimeExecution request=requireFound(claim.executionId());
            requireClaim(request,claim);
            if(request.status()!=ExecutionStatus.RUNNING || request.cancelRequestedAt()!=null)
                throw new IllegalStateException("Continuation request must be running");
            requireSemanticsCompatible(request, RequestSemantics.CONTINUE, ExecutionMode.REACT, RoutingSource.CONTINUATION_CONTROL);
            if("continuation_control".equals(request.requestKind())) {
                if(java.util.Objects.equals(targetId,request.controlTargetExecutionId())
                        && java.util.Objects.equals(generation,request.controlTargetGeneration())
                        && claim.ownerId().equals(request.controlOwnerId())) return request;
                throw new IllegalStateException("Continuation target already committed");
            }
            RuntimeExecution target=requireFound(targetId);
            if(!sameContinuationScope(request,target) || !target.awaitingInput() || target.cancelRequestedAt()!=null
                    || target.recoveryGeneration()!=generation || !"task".equals(target.requestKind()))
                throw new IllegalStateException("Continuation target is unavailable or stale");
            try(var update=connection.prepareStatement("""
                    UPDATE runtime_executions SET request_kind='continuation_control',control_target_execution_id=?,
                        control_target_generation=?,control_owner_id=?,current_policy_input=?,updated_at=?,
                        request_semantics='continue',selected_mode='react',routing_source='continuation_control' WHERE id=?
                    """)) {
                update.setString(1,targetId); update.setInt(2,generation); update.setString(3,claim.ownerId());
                update.setString(4,request.submittedInput()); update.setString(5,Instant.now().toString()); update.setString(6,request.id());
                update.executeUpdate();
            }
            return requireFound(request.id());
        });
    }

    public synchronized ContinuationReleaseResult releaseContinuation(WorkspaceOwnerLease owner,
            String requestId, String targetId, int generation) throws SQLException {
        verifyOwner(owner);
        return inTransaction(() -> {
            RuntimeExecution request=find(requestId).orElse(null), target=find(targetId).orElse(null);
            if(request==null || target==null || !sameContinuationScope(request,target)
                    || !owner.identity().key().equals(request.workspaceKey()) || !owner.ownerId().equals(request.ownerId())
                    || !owner.ownerId().equals(request.controlOwnerId()) || !"continuation_control".equals(request.requestKind())
                    || !targetId.equals(request.controlTargetExecutionId())
                    || !java.util.Objects.equals(generation,request.controlTargetGeneration())
                    || target.recoveryGeneration()!=generation || target.terminal() || target.cancelRequestedAt()!=null)
                return ContinuationReleaseResult.STALE;
            if(request.status()!=ExecutionStatus.COMPLETED || request.outcome()!=ExecutionOutcome.SUCCEEDED)
                return ContinuationReleaseResult.NOT_READY;
            if(requestId.equals(target.continuationRequestId())) return ContinuationReleaseResult.ALREADY_RELEASED;
            if(!target.awaitingInput()) return ContinuationReleaseResult.STALE;
            try(var update=connection.prepareStatement("""
                    UPDATE runtime_executions SET dispatch_state='ready',continuation_request_id=?,current_policy_input=?,updated_at=?
                    WHERE id=? AND status='enqueued' AND dispatch_state='awaiting_input' AND recovery_generation=?
                      AND cancel_requested_at IS NULL
                    """)) {
                update.setString(1,requestId); update.setString(2,request.submittedInput()); update.setString(3,Instant.now().toString());
                update.setString(4,targetId); update.setInt(5,generation);
                return update.executeUpdate()==1 ? ContinuationReleaseResult.RELEASED : ContinuationReleaseResult.STALE;
            }
        });
    }

    private static boolean sameContinuationScope(RuntimeExecution request, RuntimeExecution target) {
        return !request.id().equals(target.id()) && request.workspaceKey()!=null
                && request.workspaceKey().equals(target.workspaceKey()) && request.sessionId()!=null
                && request.sessionId().equals(target.sessionId()) && !request.legacyUnbound() && !target.legacyUnbound();
    }

    /** Fixes semantics before user persistence or any answer/tool request. CONTINUE requires target CAS. */
    public synchronized RuntimeExecution commitRequestSemantics(ExecutionClaim claim, RequestSemantics semantics) throws SQLException {
        java.util.Objects.requireNonNull(semantics);
        if (semantics == RequestSemantics.CONTINUE) throw new IllegalArgumentException("Use prepareContinuation for CONTINUE");
        return inTransaction(() -> {
            RuntimeExecution current = requireFound(claim.executionId());
            requireClaim(current, claim);
            if (current.status() != ExecutionStatus.RUNNING || current.cancelRequestedAt() != null)
                throw new IllegalStateException("Semantics require an active uncanceled claim");
            ExecutionMode mode = semantics == RequestSemantics.NEW_REQUEST ? null : ExecutionMode.REACT;
            RoutingSource source = semantics == RequestSemantics.STATUS_QUESTION ? RoutingSource.AUTO_MODEL
                    : semantics == RequestSemantics.CLARIFY ? RoutingSource.CONTINUATION_CONTROL : null;
            requireSemanticsCompatible(current, semantics, mode, source);
            if (current.requestSemantics() == semantics) return current;
            try (var update = connection.prepareStatement("UPDATE runtime_executions SET request_semantics=?,selected_mode=COALESCE(?,selected_mode),routing_source=COALESCE(?,routing_source),updated_at=? WHERE id=?")) {
                update.setString(1, encode(semantics)); update.setString(2, encode(mode)); update.setString(3, encode(source));
                update.setString(4, Instant.now().toString()); update.setString(5, current.id()); update.executeUpdate();
            }
            return requireFound(current.id());
        });
    }

    private static void requireSemanticsCompatible(RuntimeExecution current, RequestSemantics semantics,
            ExecutionMode mode, RoutingSource source) {
        if (current.requestSemantics() != null && current.requestSemantics() != semantics)
            throw new IllegalStateException("Request semantics already committed");
        if (mode != null && current.selectedMode() != null
                && (current.selectedMode() != mode || current.routingSource() != source))
            throw new IllegalStateException("Request route conflicts with semantics");
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
        return complete(claim, requestedOutcome, result, error, (execution, outcome) -> { });
    }

    synchronized RuntimeExecution completeWithEnvelope(ExecutionClaim claim, ExecutionOutcome outcome,
            String result, String error, BeforeCompletion before) throws SQLException {
        return complete(claim, outcome, result, error, before);
    }

    private RuntimeExecution complete(ExecutionClaim claim, ExecutionOutcome requestedOutcome, String result,
            String error, BeforeCompletion before) throws SQLException {
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
            ExecutionOutcome outcome = current.cancelRequestedAt() == null
                    ? requestedOutcome : ExecutionOutcome.CANCELED;
            ExecutionStatus status = statusFor(outcome);
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

    private static ExecutionStatus statusFor(ExecutionOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED, PARTIAL -> ExecutionStatus.COMPLETED;
            case FAILED, REJECTED -> ExecutionStatus.FAILED;
            case CANCELED -> ExecutionStatus.CANCELED;
        };
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
            if (!migrationApplied(2)) {
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
                recordMigration(2);
            }
            if (!migrationApplied(3)) {
                String[] columns = {
                        "request_kind TEXT NOT NULL DEFAULT 'task' CHECK(request_kind IN ('task','continuation_control'))",
                        "dispatch_state TEXT NOT NULL DEFAULT 'ready' CHECK(dispatch_state IN ('ready','awaiting_input'))",
                        "recovery_generation INTEGER NOT NULL DEFAULT 0 CHECK(recovery_generation >= 0)",
                        "continuation_request_id TEXT REFERENCES runtime_executions(id)",
                        "control_target_execution_id TEXT REFERENCES runtime_executions(id)",
                        "control_target_generation INTEGER CHECK(control_target_generation >= 0)",
                        "control_owner_id TEXT",
                        "current_policy_input TEXT"
                };
                try (Statement statement = connection.createStatement()) {
                    for (String column : columns) statement.execute("ALTER TABLE runtime_executions ADD COLUMN " + column);
                }
                recordMigration(3);
            }
            if (!migrationApplied(4)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE runtime_executions ADD COLUMN request_semantics TEXT CHECK(request_semantics IS NULL OR request_semantics IN ('new_request','status_question','continue','clarify'))");
                }
                recordMigration(4);
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
                instant(rows.getString("updated_at")),
                rows.getString("request_kind"), rows.getString("dispatch_state"), rows.getInt("recovery_generation"),
                rows.getString("continuation_request_id"), rows.getString("control_target_execution_id"),
                nullableLong(rows, "control_target_generation") == null ? null : rows.getInt("control_target_generation"),
                rows.getString("control_owner_id"), rows.getString("current_policy_input"),
                RequestSemantics.decode(rows.getString("request_semantics")));
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
