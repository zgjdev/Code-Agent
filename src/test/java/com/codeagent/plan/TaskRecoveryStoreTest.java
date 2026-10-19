package com.codeagent.plan;

import com.codeagent.model.recovery.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskRecoveryStoreTest {

    @TempDir
    Path temp;

    private PlanStateStore plans() throws Exception {
        return new PlanStateStore(temp.resolve("plans.db"));
    }

    private void save(PlanStateStore store, String id, String execution) throws Exception {
        var plan = new ExecutionPlan(id, "private goal");
        plan.addTask(new Task("same", "private description", Task.TaskType.FILE_READ, List.of()));
        assertTrue(plan.computeExecutionOrder());
        store.savePlan(temp, "parent", execution, "private policy", plan);
        plan.markCompleted();
        store.checkpointPlan(plan);
    }

    @Test
    void firstMigrationAndBindingsAreSafeAcrossFourConcurrentStores() throws Exception {
        var plans = plans();
        save(plans, "p", "e");
        var start = new java.util.concurrent.CyclicBarrier(4);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<TaskAttemptBinding>>();
            for (int i = 0; i < 4; i++) {
                final int number = i;
                futures.add(workers.submit(() -> {
                    start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    Path alias = plans.dbPath().getParent().resolve(".").resolve(plans.dbPath().getFileName());
                    var store = new TaskRecoveryStore(number % 2 == 0 ? plans.dbPath() : alias);
                    return store.bindAttempt(temp, "parent", "e", "p", "same", "child-" + number, null, "owner", 1);
                }));
            }
            var ids = new HashSet<String>();
            for (var future : futures) ids.add(future.get(20, java.util.concurrent.TimeUnit.SECONDS).attemptId());
            assertEquals(4, ids.size());
            assertEquals(4, TaskRecoveryStore.openReadOnly(plans.dbPath())
                    .findTaskAttempts(temp, "parent", "e", "p", "same").size());
            Path backup = plans.dbPath().resolveSibling("plans.db.recovery-format0.backup");
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + backup.toUri() + "?mode=ro"); var s = c.createStatement()) {
                try (var r = s.executeQuery("PRAGMA integrity_check")) { assertEquals("ok", r.getString(1)); }
                try (var r = s.executeQuery("SELECT count(*) FROM plan_runs")) { assertEquals(1, r.getInt(1)); }
                try (var r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='plan_recovery_meta'")) {
                    assertEquals(0, r.getInt(1), "backup must precede additive migration");
                }
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void failedMigrationRollsBackAndReleasesLockForRetry() throws Exception {
        var plans = plans();
        save(plans, "p", "e");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + plans.dbPath()); var s = c.createStatement()) {
            s.execute("CREATE TABLE task_recovery_invocations(dummy TEXT)");
        }
        assertThrows(SQLException.class, () -> new TaskRecoveryStore(plans.dbPath()));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + plans.dbPath()); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name IN ('plan_recovery_meta','task_execution_attempts')")) {
                assertEquals(0, r.getInt(1), "partially created schema must roll back");
            }
            s.execute("DROP TABLE task_recovery_invocations");
        }
        var retry = new TaskRecoveryStore(plans.dbPath());
        assertNotNull(retry.bindAttempt(temp, "parent", "e", "p", "same", "retry-child", null, "owner", 1));
        try (var files = java.nio.file.Files.list(temp)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("plans.db.recovery-backup-")));
        }
    }

    @Test
    void firstMigrationIsSerializedAcrossIndependentJvmProcesses() throws Exception {
        var plans = plans();
        save(plans, "p", "e");
        var children = new ArrayList<Process>();
        Path start = temp.resolve("start");
        try {
            for (int i = 0; i < 4; i++) {
                Path ready = temp.resolve("ready-" + i);
                children.add(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        MigrationProcess.class.getName(), plans.dbPath().toString(), temp.toString(), "process-child-" + i,
                        ready.toString(), start.toString()).redirectErrorStream(true).redirectOutput(temp.resolve("process-" + i + ".log").toFile()).start());
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            for (int i = 0; i < 4; i++) {
                while (!java.nio.file.Files.exists(temp.resolve("ready-" + i)) && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(java.nio.file.Files.exists(temp.resolve("ready-" + i)), "child JVM reached controlled start barrier");
            }
            java.nio.file.Files.createFile(start);
            for (int i = 0; i < 4; i++) {
                assertTrue(children.get(i).waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, children.get(i).exitValue(), java.nio.file.Files.readString(temp.resolve("process-" + i + ".log")));
            }
            assertEquals(4, TaskRecoveryStore.openReadOnly(plans.dbPath()).findTaskAttempts(temp, "parent", "e", "p", "same").size());
        } finally {
            for (var child : children) if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    public static final class MigrationProcess {
        public static void main(String[] args) throws Exception {
            java.nio.file.Files.createFile(Path.of(args[3]));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (!java.nio.file.Files.exists(Path.of(args[4]))) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("migration barrier timeout");
                Thread.sleep(10);
            }
            var store = new TaskRecoveryStore(Path.of(args[0]));
            store.bindAttempt(Path.of(args[1]), "parent", "e", "p", "same", args[2], null, "owner", 1);
        }
    }

    @Test
    void readonlyStateKeepsRecordedStatusAndDoesNotRepairDatabase() throws Exception {
        var store = plans();
        var plan = new ExecutionPlan("p", "private goal");
        var task = new Task("same", "private description", Task.TaskType.FILE_READ, List.of());
        plan.addTask(task);
        plan.computeExecutionOrder();
        task.markStarted();
        store.savePlan(temp, "parent", "execution", "policy", plan);
        byte[] before = java.nio.file.Files.readAllBytes(store.dbPath());
        var snapshot = store.readRecoveryState(temp, "parent");
        assertEquals("RUNNING", snapshot.get(0).tasks().get(0).recordedStatus());
        assertEquals("INTERRUPTED", snapshot.get(0).tasks().get(0).recoveryStatus());
        assertEquals("METADATA_ONLY", snapshot.get(0).tasks().get(0).completeness().name());
        assertFalse(snapshot.toString().contains("private"));
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(store.dbPath()));
        assertTrue(store.readRecoveryState(temp, "other-parent").isEmpty());
    }

    @Test
    void exactBindingAndSourceChainCannotCrossPlanOrExecution() throws Exception {
        var plans = plans();
        save(plans, "p1", "e1");
        save(plans, "p2", "e2");
        var store = new TaskRecoveryStore(plans.dbPath());
        var a = store.bindAttempt(temp, "parent", "e1", "p1", "same", "child1", null, "owner", 1);
        var b = store.bindAttempt(temp, "parent", "e2", "p2", "same", "child2", null, "owner", 1);
        assertNotEquals(a.attemptId(), b.attemptId());
        assertThrows(SQLException.class, () -> store.bindAttempt(temp, "parent", "e2", "p1", "same", "child3", null, "owner", 1));
        assertThrows(SQLException.class, () -> store.bindAttempt(temp, "parent", "e1", "p1", "same", "child4", b.attemptId(), "owner", 1));
        var next = store.bindAttempt(temp, "parent", "e1", "p1", "same", "child5", a.attemptId(), "owner", 1);
        assertEquals(a.attemptId(), next.sourceAttemptId());
        assertEquals(a, store.findAttempt(a.attemptId()).orElseThrow());
        assertTrue(store.findTaskAttempts(temp, "parent", "e1", "p2", "same").isEmpty());
        assertFalse(a.toString().contains("private"));
    }

    @Test
    void durableStartWithoutResultIsUnknownAndFutureFormatFailsClosed() throws Exception {
        var plans = plans();
        save(plans, "p", "e");
        var store = new TaskRecoveryStore(plans.dbPath());
        var a = store.bindAttempt(temp, "parent", "e", "p", "same", "child", null, "owner", 1);
        assertThrows(SQLException.class,()->store.bindAttempt(temp,"parent","e","p","same","invalid",null,null,0));
        store.recordStarted(a, "call", 2, "execute_command", 5, "hash");
        var checkpoint = store.readCheckpoint(a);
        assertEquals(1, checkpoint.unknowns().size());
        assertTrue(checkpoint.facts().isEmpty());
        store.recordResult(a, "call", 2, "execute_command", true, false, 0, 6, "result-hash");
        checkpoint = store.readCheckpoint(a);
        assertTrue(checkpoint.unknowns().isEmpty());
        assertEquals(RecoveryFact.VerificationClass.HISTORICAL, checkpoint.facts().get(0).verificationClass());
        assertEquals(RecoveryFact.Kind.COMMAND_SUCCEEDED, checkpoint.facts().get(0).kind());
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + plans.dbPath());
            var s = c.createStatement()) {
            s.executeUpdate("UPDATE plan_recovery_meta SET format_version=2");
        }
        assertThrows(SQLException.class, () -> store.readCheckpoint(a));
        assertThrows(SQLException.class, () -> new TaskRecoveryStore(plans.dbPath()));
    }

    @Test
    void corruptCheckpointAndSourceBeyondCommittedPrefixAreRejected() throws Exception {
        var plans = plans();
        save(plans, "p", "e");
        var store = new TaskRecoveryStore(plans.dbPath());
        var a = store.bindAttempt(temp, "parent", "e", "p", "same", "child", null, "owner", 1);
        store.recordStarted(a, "call", 0, "read_file", 3, "hash");
        assertThrows(SQLException.class, () -> store.checkpoint(a, 2, "TOOL_BATCH"));
        store.checkpoint(a, 3, "TOOL_BATCH");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + plans.dbPath());
            var s = c.createStatement()) {
            s.executeUpdate("UPDATE task_recovery_checkpoints SET facts_json='broken-private-payload'");
        }
        var failure = assertThrows(SQLException.class, () -> store.readCheckpoint(a));
        assertFalse(failure.toString().contains("broken-private"));
    }
}
