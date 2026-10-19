package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.RoutingSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeExecutionStoreTest {

    @Test
    void migratesExecutionIdentitySchemaToVersionFour(@TempDir Path tempDir) throws Exception {
        try (RuntimeExecutionStore ignored = new RuntimeExecutionStore(tempDir.resolve("tasks.db"));
             var connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("tasks.db"));
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT MAX(version) FROM runtime_schema_migrations")) {
            assertTrue(rows.next());
            assertEquals(4, rows.getInt(1), "durable request semantics require schema version 4");
        }
    }

    @Test
    void enqueuesWithMonotonicSessionOrdinalAndClaimsOnlyCurrentWorkspace(@TempDir Path tempDir)
            throws Exception {
        Path db = tempDir.resolve("tasks.db");
        Path workspaceA = tempDir.resolve("a").toAbsolutePath().normalize();
        Path workspaceB = tempDir.resolve("b").toAbsolutePath().normalize();
        Files.createDirectories(workspaceA);
        Files.createDirectories(workspaceB);
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(db)) {
            RuntimeExecution a1 = store.enqueue(workspaceA, "session-a", "one", null);
            RuntimeExecution a2 = store.enqueue(workspaceA, "session-a", "two", ExecutionMode.PLAN);
            store.enqueue(workspaceB, "session-b", "other", null);

            assertEquals(0L, a1.ordinal());
            assertEquals(1L, a2.ordinal());
            RuntimeExecution claimed = store.claimNext(workspaceA).orElseThrow();
            assertEquals(a1.id(), claimed.id());
            assertEquals(ExecutionStatus.RUNNING, claimed.status());
            assertTrue(store.claimNext(workspaceA).isEmpty(), "one workspace may have only one running execution");
            assertEquals("session-b", store.claimNext(workspaceB).orElseThrow().sessionId());
        }
    }

    @Test
    void bindsEachSessionToOneCanonicalWorkspace(@TempDir Path tempDir) throws Exception {
        Path workspaceA = Files.createDirectory(tempDir.resolve("a"));
        Path workspaceB = Files.createDirectory(tempDir.resolve("b"));
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"))) {
            RuntimeExecution first = store.enqueue(workspaceA, "session-a", "one", null);
            RuntimeExecution alias = store.enqueue(
                    workspaceA.resolve("..").resolve("a"), "session-a", "two", null);

            assertEquals(first.workspaceKey(), alias.workspaceKey());
            assertThrows(IllegalStateException.class,
                    () -> store.enqueue(workspaceB, "session-a", "wrong workspace", null));
            assertEquals(2, store.list(workspaceA, 10).size());
        }
    }

    @Test
    void eachClaimCarriesAnOwnerAndAnAttemptFence(@TempDir Path tempDir) throws Exception {
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"))) {
            store.enqueue(tempDir, "session-a", "one", null);
            RuntimeExecution claimed = store.claimNext(tempDir).orElseThrow();

            assertNotNull(claimed.ownerId());
            assertTrue(claimed.attempt() > 0);
        }
    }

    @Test
    void routingDecisionIsDurableIdempotentAndRejectsConflict(@TempDir Path tempDir) throws Exception {
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"))) {
            store.enqueue(tempDir, "session", "prompt", null);
            ExecutionClaim claim = store.claimNext(tempDir).orElseThrow().claim();

            RuntimeExecution first = store.commitRoutingDecision(
                    claim, ExecutionMode.REACT, RoutingSource.AUTO_MODEL);
            RuntimeExecution repeated = store.commitRoutingDecision(
                    claim, ExecutionMode.REACT, RoutingSource.AUTO_MODEL);

            assertEquals(ExecutionMode.REACT, first.selectedMode());
            assertEquals(first, repeated);
            assertThrows(IllegalStateException.class, () -> store.commitRoutingDecision(
                    claim, ExecutionMode.PLAN, RoutingSource.AUTO_MODEL));
        }
    }

    @Test
    void cancelRequestWinsTerminalRace(@TempDir Path tempDir) throws Exception {
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"))) {
            RuntimeExecution execution = store.enqueue(tempDir, "session", "prompt", null);
            ExecutionClaim claim = store.claimNext(tempDir).orElseThrow().claim();

            assertEquals(CancelRequestResult.REQUESTED, store.requestCancel(execution.id()));
            RuntimeExecution terminal = store.complete(
                    claim, ExecutionOutcome.SUCCEEDED, "done", null);

            assertEquals(ExecutionStatus.CANCELED, terminal.status());
            assertEquals(ExecutionOutcome.CANCELED, terminal.outcome());
            assertNotNull(terminal.cancelRequestedAt());
        }
    }

    @Test
    void terminalCompletionIsIdempotentOnlyForTheSameOutcome(@TempDir Path tempDir) throws Exception {
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"))) {
            store.enqueue(tempDir, "session", "prompt", null);
            ExecutionClaim claim = store.claimNext(tempDir).orElseThrow().claim();
            RuntimeExecution completed = store.complete(
                    claim, ExecutionOutcome.SUCCEEDED, "done", null);

            assertEquals(completed, store.complete(
                    claim, ExecutionOutcome.SUCCEEDED, "done", null));
            assertThrows(IllegalStateException.class, () -> store.complete(
                    claim, ExecutionOutcome.FAILED, null, "different"));
        }
    }

    @Test
    void migratesLegacyRowsAsUnboundWithoutClaimingThem(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("tasks.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE runtime_tasks (
                        id TEXT PRIMARY KEY, status TEXT NOT NULL, prompt TEXT NOT NULL,
                        result TEXT, error TEXT, created_at TEXT NOT NULL, started_at TEXT,
                        finished_at TEXT, updated_at TEXT, duration_ms INTEGER DEFAULT 0
                    )
                    """);
            statement.execute("""
                    INSERT INTO runtime_tasks(id,status,prompt,created_at)
                    VALUES('legacy_1','enqueued','old prompt','2026-01-01T00:00:00Z')
                    """);
        }

        try (RuntimeExecutionStore store = new RuntimeExecutionStore(db)) {
            RuntimeExecution legacy = store.find("legacy_1").orElseThrow();
            assertTrue(legacy.legacyUnbound());
            assertNull(legacy.sessionId());
            assertNull(legacy.ordinal());
            assertTrue(store.claimNext(tempDir).isEmpty());
        }
    }

    @Test
    void unknownLegacyStatusRollsBackMigration(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("tasks.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE runtime_tasks (id TEXT PRIMARY KEY, status TEXT NOT NULL, prompt TEXT NOT NULL, created_at TEXT NOT NULL)");
            statement.execute("INSERT INTO runtime_tasks VALUES('bad','mystery','prompt','2026-01-01T00:00:00Z')");
        }

        assertThrows(Exception.class, () -> new RuntimeExecutionStore(db));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM runtime_tasks")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void listsWorkspaceExecutionsAndReportsBusySession(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Files.createDirectories(tempDir.resolve("other"));
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"))) {
            RuntimeExecution first = store.enqueue(workspace, "session-a", "one", null);
            RuntimeExecution second = store.enqueue(workspace, "session-a", "two", null);
            store.enqueue(tempDir.resolve("other"), "session-b", "other", null);

            assertTrue(store.hasNonTerminal("session-a"));
            assertTrue(store.running(workspace).isEmpty());
            assertEquals(List.of(second.id(), first.id()),
                    store.list(workspace, 10).stream().map(RuntimeExecution::id).toList());

            RuntimeExecution claimed = store.claimNext(workspace).orElseThrow();
            assertEquals(first.id(), store.running(workspace).orElseThrow());
            store.complete(claimed.claim(), ExecutionOutcome.SUCCEEDED, "done", null);
            assertTrue(store.hasNonTerminal("session-a"), "the second execution remains queued");
            store.requestCancel(second.id());
            assertFalse(store.hasNonTerminal("session-a"));
        }
    }

    @Test
    void adoptsLegacyPlanWithDeterministicExecutionIdentity(@TempDir Path tempDir) throws Exception {
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"))) {
            RuntimeExecution first = store.adoptPlanExecution(
                    "legacy-plan-p1", tempDir, "session", "original input");
            RuntimeExecution repeated = store.adoptPlanExecution(
                    "legacy-plan-p1", tempDir, "session", "original input");

            assertEquals(first, repeated);
            assertEquals(ExecutionMode.PLAN, first.explicitMode());
            assertThrows(IllegalStateException.class, () -> store.adoptPlanExecution(
                    "legacy-plan-p1", tempDir, "other-session", "different"));
        }
    }
}
