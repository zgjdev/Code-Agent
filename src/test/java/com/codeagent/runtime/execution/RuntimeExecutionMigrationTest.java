package com.codeagent.runtime.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeExecutionMigrationTest {
    @Test
    void migratesV1RowsForExistingWorkspaceAndPreservesExecutionData(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path database = tempDir.resolve("runtime.db");
        createV1Database(database);
        insertV1Execution(database, "exec_1", workspace.toString(), "session-a", 0, "enqueued");

        try (RuntimeExecutionStore store = new RuntimeExecutionStore(database)) {
            RuntimeExecution execution = store.find("exec_1").orElseThrow();
            WorkspaceIdentity identity = new WorkspaceIdentityResolver().resolve(workspace);

            assertEquals(identity.key(), execution.workspaceKey());
            assertEquals(workspace.toRealPath().toString(), execution.workspace());
            assertEquals("session-a", execution.sessionId());
            assertEquals(0L, execution.ordinal());
            assertEquals("original input", execution.submittedInput());
            assertEquals(ExecutionStatus.ENQUEUED, execution.status());
            assertTrue(store.list(workspace, 10).stream().anyMatch(value -> value.id().equals("exec_1")));
        }
    }

    @Test
    void leavesRowsUnclaimableWhenWorkspaceIdentityCannotBeResolved(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("runtime.db");
        createV1Database(database);
        insertV1Execution(database, "exec_missing", tempDir.resolve("missing").toString(),
                "session-missing", 0, "enqueued");

        try (RuntimeExecutionStore store = new RuntimeExecutionStore(database)) {
            RuntimeExecution execution = store.find("exec_missing").orElseThrow();
            assertNull(execution.workspaceKey());
            assertEquals(ExecutionStatus.ENQUEUED, execution.status());
            assertEquals(java.util.List.of(execution), store.unresolvedWorkspaceExecutions(10));
        }
    }

    @Test
    void registrationBackfillsOnlyExplicitlyOwnedWorkspace(@TempDir Path tempDir) throws Exception {
        Path workspaceA = tempDir.resolve("a");
        Path workspaceB = tempDir.resolve("b");
        Path workspaceC = tempDir.resolve("c");
        Path database = tempDir.resolve("runtime.db");
        createV1Database(database);
        insertV1Execution(database, "exec_a", workspaceA.toString(), "session-a", 0, "enqueued");
        insertV1Execution(database, "exec_b", workspaceB.toString(), "session-b", 0, "enqueued");
        insertV1Execution(database, "exec_c", workspaceC.toString(), "session-b", 1, "enqueued");
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(database)) {
            Files.createDirectory(workspaceA);
            Files.createDirectory(workspaceB);
            Files.createDirectory(workspaceC);
            WorkspaceIdentity identity = new WorkspaceIdentityResolver().resolve(workspaceA);
            try (WorkspaceOwnerLease owner = WorkspaceOwnerLease.acquire(tempDir.resolve("locks"), identity, "owner-a")) {
                store.registerWorkspace(owner);
                assertEquals(identity.key(), store.find("exec_a").orElseThrow().workspaceKey());
                assertNull(store.find("exec_b").orElseThrow().workspaceKey());
                assertTrue(store.sessionWorkspace("session-b").isEmpty());
            }
        }
    }

    @Test
    void rollsBackWhenLegacySessionHasMultipleWorkspaces(@TempDir Path tempDir) throws Exception {
        Path workspaceA = Files.createDirectory(tempDir.resolve("a"));
        Path workspaceB = Files.createDirectory(tempDir.resolve("b"));
        Path database = tempDir.resolve("runtime.db");
        createV1Database(database);
        insertV1Execution(database, "exec_a", workspaceA.toString(), "session-shared", 0, "enqueued");
        insertV1Execution(database, "exec_b", workspaceB.toString(), "session-shared", 1, "enqueued");

        assertThrows(java.sql.SQLException.class, () -> new RuntimeExecutionStore(database));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM runtime_executions")) {
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
        }
    }

    @Test
    void migrationIsRepeatableAndCreatesWorkspaceAndSessionRunningConstraints(@TempDir Path tempDir)
            throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path database = tempDir.resolve("runtime.db");
        createV1Database(database);
        insertV1Execution(database, "exec_1", workspace.toString(), "session-a", 0, "enqueued");

        try (RuntimeExecutionStore ignored = new RuntimeExecutionStore(database)) {
            // First open upgrades v1 to v2.
        }
        try (RuntimeExecutionStore ignored = new RuntimeExecutionStore(database);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT name, sql FROM sqlite_master
                     WHERE type = 'index'
                       AND name IN ('uq_runtime_execution_running_workspace',
                                    'uq_runtime_execution_running_session')
                     ORDER BY name
                     """)) {
            assertTrue(rows.next());
            assertEquals("uq_runtime_execution_running_session", rows.getString(1));
            assertTrue(rows.getString(2).contains("session_id"));
            assertTrue(rows.next());
            assertEquals("uq_runtime_execution_running_workspace", rows.getString(1));
            assertTrue(rows.getString(2).contains("workspace_key"));
            assertFalse(rows.next());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA foreign_key_list(runtime_executions)")) {
            assertTrue(rows.next());
            assertEquals("runtime_workspaces", rows.getString("table"));
            assertEquals("workspace_key", rows.getString("from"));
        }
    }

    private static void createV1Database(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE runtime_schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            statement.execute("INSERT INTO runtime_schema_migrations(version, applied_at) VALUES(1, '2026-01-01T00:00:00Z')");
            statement.execute("""
                    CREATE TABLE runtime_executions (
                        id TEXT PRIMARY KEY, workspace TEXT NOT NULL, session_id TEXT, ordinal INTEGER,
                        status TEXT NOT NULL CHECK(status IN ('enqueued','running','completed','failed','canceled')),
                        submitted_input TEXT NOT NULL, resolved_task_input TEXT, explicit_mode TEXT,
                        selected_mode TEXT, routing_source TEXT, outcome TEXT, result TEXT, error TEXT,
                        attempt INTEGER NOT NULL DEFAULT 0,
                        legacy_unbound INTEGER NOT NULL DEFAULT 0 CHECK(legacy_unbound IN (0,1)),
                        cancel_requested_at TEXT, interruption_reason TEXT, created_at TEXT NOT NULL,
                        started_at TEXT, finished_at TEXT, updated_at TEXT NOT NULL,
                        UNIQUE(session_id, ordinal),
                        CHECK((legacy_unbound = 1 AND session_id IS NULL AND ordinal IS NULL)
                           OR (legacy_unbound = 0 AND session_id IS NOT NULL AND ordinal IS NOT NULL))
                    )
                    """);
            statement.execute("CREATE INDEX idx_runtime_execution_queue ON runtime_executions(workspace, status, created_at, ordinal)");
            statement.execute("CREATE UNIQUE INDEX uq_runtime_execution_running_workspace ON runtime_executions(workspace) WHERE status='running' AND legacy_unbound=0");
            statement.execute("CREATE UNIQUE INDEX uq_runtime_execution_running_session ON runtime_executions(session_id) WHERE status='running' AND legacy_unbound=0 AND session_id IS NOT NULL");
        }
    }

    private static void insertV1Execution(
            Path database, String id, String workspace, String sessionId, int ordinal, String status) throws Exception {
        String now = Instant.parse("2026-01-01T00:00:00Z").toString();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement("""
                     INSERT INTO runtime_executions(
                         id, workspace, session_id, ordinal, status, submitted_input, attempt, legacy_unbound,
                         created_at, updated_at)
                     VALUES(?, ?, ?, ?, ?, 'original input', 0, 0, ?, ?)
                     """)) {
            statement.setString(1, id);
            statement.setString(2, workspace);
            statement.setString(3, sessionId);
            statement.setInt(4, ordinal);
            statement.setString(5, status);
            statement.setString(6, now);
            statement.setString(7, now);
            statement.executeUpdate();
        }
    }
}
