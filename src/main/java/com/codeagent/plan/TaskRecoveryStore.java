package com.codeagent.plan;

import com.codeagent.model.recovery.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Additive exact-attempt index in the Plan DB. It never changes Plan/Task status.
 */
public class TaskRecoveryStore implements com.codeagent.history.TaskRecoverySourceAccess {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path path;

    public TaskRecoveryStore(Path path) throws SQLException {
        this(path, false);
    }

    private TaskRecoveryStore(Path path, boolean readOnly) throws SQLException {
        try {
            this.path = Objects.requireNonNull(path).toRealPath();
        } catch (IOException failure) {
            throw new SQLException("Plan recovery database unavailable");
        }
        if (!Files.isRegularFile(this.path))
            throw new SQLException("Plan recovery database unavailable");
        if (!readOnly)
            initialize();
    }

    /**
     * No schema creation, migration or writable connection. Missing format means legacy metadata only.
     */
    public static TaskRecoveryStore openReadOnly(Path path) throws SQLException {
        return new TaskRecoveryStore(path, true);
    }

    private Connection read() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + path.toUri() + "?mode=ro");
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA query_only=ON");
        }
        return c;
    }

    private Connection write() throws SQLException {
        // Acquire the write reservation before reading mutable indexes (including MAX(attempt_no)).
        var properties = new Properties();
        properties.setProperty("busy_timeout", "10000");
        properties.setProperty("transaction_mode", "IMMEDIATE");
        return DriverManager.getConnection("jdbc:sqlite:" + path, properties);
    }

    private boolean checkFormat(Connection c, boolean required) throws SQLException {
        try (var s = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='plan_recovery_meta'");
            var r = s.executeQuery()) {
            if (!r.next()) {
                if (required)
                    throw new SQLException("Recovery format unavailable");
                return false;
            }
        }
        try (var s = c.createStatement();
            var r = s.executeQuery("SELECT format_version FROM plan_recovery_meta")) {
            if (!r.next() || r.getInt(1) != 1 || r.next())
                throw new SQLException("Unsupported recovery format");
        }
        return true;
    }

    private void initialize() throws SQLException {
        // Stable sidecar inode coordinates distinct stores/JVMs. Never unlink it on release:
        // deleting a lock file permits waiters and new arrivals to lock different inodes.
        Path lockPath = path.resolveSibling(path.getFileName() + ".recovery-migration.lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = awaitMigrationLock(channel);
             Connection c = write()) {
            if (checkFormat(c, false))
                return;
            backupFormatZero(c);
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                // The transaction's reservation protects this authoritative format recheck.
                if (checkFormat(c, false)) {
                    c.commit();
                    return;
                }
                s.execute("CREATE TABLE plan_recovery_meta(format_version INTEGER NOT NULL)");
                s.execute("INSERT INTO plan_recovery_meta VALUES(1)");
                s.execute("""
                    CREATE TABLE task_execution_attempts(
                     workspace TEXT NOT NULL,parent_session_id TEXT NOT NULL,execution_id TEXT NOT NULL,
                     plan_id TEXT NOT NULL,task_id TEXT NOT NULL,attempt_no INTEGER NOT NULL,
                     attempt_id TEXT NOT NULL UNIQUE,child_session_id TEXT NOT NULL UNIQUE,
                     runtime_owner_id TEXT,runtime_attempt INTEGER NOT NULL,
                     source_attempt_id TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,
                     last_checkpoint_id TEXT,PRIMARY KEY(plan_id,task_id,attempt_no))
                    """);
                s.execute("""
                    CREATE TABLE task_recovery_invocations(
                     attempt_id TEXT NOT NULL,call_id TEXT NOT NULL,call_ordinal INTEGER NOT NULL,
                     tool_name TEXT NOT NULL,start_sequence INTEGER NOT NULL,start_hash TEXT NOT NULL,
                     result_sequence INTEGER,result_hash TEXT,successful INTEGER,timed_out INTEGER,
                     exit_code INTEGER,observed_at INTEGER NOT NULL,PRIMARY KEY(attempt_id,call_id))
                    """);
                s.execute("""
                    CREATE TABLE task_recovery_checkpoints(
                     checkpoint_id TEXT PRIMARY KEY,attempt_id TEXT NOT NULL,checkpoint_no INTEGER NOT NULL,
                     child_last_sequence INTEGER NOT NULL,created_at INTEGER NOT NULL,phase TEXT NOT NULL,
                     facts_json TEXT NOT NULL,unknowns_json TEXT NOT NULL,evidence_refs_json TEXT NOT NULL,
                     summary_hints_json TEXT NOT NULL,UNIQUE(attempt_id,checkpoint_no))
                    """);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw new SQLException("Recovery schema migration failed");
            }
        } catch (IOException failure) {
            throw new SQLException("Recovery migration lock or backup unavailable");
        }
    }

    private static FileLock awaitMigrationLock(FileChannel channel) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException busyInThisJvm) {
                // File locks do not block overlapping channels in the same JVM; retry finitely.
            }
            if (System.nanoTime() >= deadline) throw new IOException("Recovery migration lock timeout");
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Recovery migration lock interrupted");
            }
        }
    }

    private void backupFormatZero(Connection c) throws SQLException, IOException {
        Path backup = path.resolveSibling(path.getFileName() + ".recovery-format0.backup");
        Path pending = path.resolveSibling(path.getFileName() + ".recovery-backup-" + UUID.randomUUID() + ".tmp");
        try {
            try (var statement = c.createStatement()) {
                statement.execute("VACUUM INTO '" + pending.toString().replace("'", "''") + "'");
            }
            try {
                Files.move(pending, backup, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(pending, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    public synchronized TaskAttemptBinding bindAttempt(Path workspace, String parent, String execution, String plan, String task, String child, String source, String owner, int runtimeAttempt) throws SQLException {
        if(owner==null||owner.isBlank()||runtimeAttempt<1)throw new SQLException("Current runtime claim identity required");
        for (String v : List.of(workspace.toAbsolutePath().normalize().toString(), parent, execution, plan, task, child)) if (v.isBlank())
            throw new SQLException("Attempt identity required");
        try (Connection c = write()) {
            checkFormat(c, true);
            c.setAutoCommit(false);
            try {
                String root = workspace.toAbsolutePath().normalize().toString();
                try (var s = c.prepareStatement("SELECT 1 FROM plan_runs r JOIN plan_tasks t ON t.plan_id=r.id WHERE r.id=? AND t.task_id=? AND r.workspace=? AND r.session_id=? AND r.execution_id=?")) {
                    s.setString(1, plan);
                    s.setString(2, task);
                    s.setString(3, root);
                    s.setString(4, parent);
                    s.setString(5, execution);
                    try (var r = s.executeQuery()) {
                        if (!r.next())
                            throw new SQLException("Attempt Plan identity mismatch");
                    }
                }
                if (source != null) {
                    var old = find(c, source).orElseThrow(() -> new SQLException("Source attempt missing"));
                    if (!old.workspace().equals(root) || !old.parentSessionId().equals(parent) || !old.executionId().equals(execution) || !old.planId().equals(plan) || !old.taskId().equals(task))
                        throw new SQLException("Source attempt identity mismatch");
                    validateChain(c, old);
                }
                int number;
                try (var s = c.prepareStatement("SELECT COALESCE(MAX(attempt_no),0)+1 FROM task_execution_attempts WHERE plan_id=? AND task_id=?")) {
                    s.setString(1, plan);
                    s.setString(2, task);
                    try (var r = s.executeQuery()) {
                        r.next();
                        number = r.getInt(1);
                    }
                }
                var binding = new TaskAttemptBinding(root, parent, execution, plan, task, number, "attempt_" + UUID.randomUUID(), child, owner, runtimeAttempt, source, System.currentTimeMillis());
                try (var s = c.prepareStatement("INSERT INTO task_execution_attempts(workspace,parent_session_id,execution_id,plan_id,task_id,attempt_no,attempt_id,child_session_id,runtime_owner_id,runtime_attempt,source_attempt_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    s.setString(1, root);
                    s.setString(2, parent);
                    s.setString(3, execution);
                    s.setString(4, plan);
                    s.setString(5, task);
                    s.setInt(6, number);
                    s.setString(7, binding.attemptId());
                    s.setString(8, child);
                    s.setString(9, owner);
                    s.setInt(10, runtimeAttempt);
                    s.setString(11, source);
                    s.setLong(12, binding.createdAt());
                    s.setLong(13, binding.createdAt());
                    s.executeUpdate();
                }
                c.commit();
                return binding;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw new SQLException("Attempt binding rejected");
            }
        }
    }

    public Optional<TaskAttemptBinding> findAttempt(String id) throws SQLException {
        try (Connection c = read()) {
            if (!checkFormat(c, false))
                return Optional.empty();
            var found = find(c, id);
            if (found.isPresent())
                validateChain(c, found.get());
            return found;
        }
    }

    public List<TaskAttemptBinding> findTaskAttempts(Path workspace, String parent, String execution, String plan, String task) throws SQLException {
        try (Connection c = read()) {
            if (!checkFormat(c, false))
                return List.of();
            var result = new ArrayList<TaskAttemptBinding>();
            try (var s = c.prepareStatement("SELECT * FROM task_execution_attempts WHERE workspace=? AND parent_session_id=? AND execution_id=? AND plan_id=? AND task_id=? ORDER BY attempt_no")) {
                s.setString(1, workspace.toAbsolutePath().normalize().toString());
                s.setString(2, parent);
                s.setString(3, execution);
                s.setString(4, plan);
                s.setString(5, task);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        if (result.size() == 200)
                            throw new SQLException("Attempt limit exceeded");
                        var a = map(r);
                        validateChain(c, a);
                        result.add(a);
                    }
                }
            }
            return List.copyOf(result);
        }
    }

    public void requireExact(TaskAttemptBinding binding) throws SQLException {
        if (!findAttempt(binding.attemptId()).filter(binding::equals).isPresent())
            throw new SQLException("Attempt identity mismatch");
    }

    private Optional<TaskAttemptBinding> find(Connection c, String id) throws SQLException {
        try (var s = c.prepareStatement("SELECT * FROM task_execution_attempts WHERE attempt_id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(map(r)) : Optional.empty();
            }
        }
    }

    private void validateChain(Connection c, TaskAttemptBinding original) throws SQLException {
        Set<String> seen = new HashSet<>();
        var current = original;
        while (current != null) {
            if (!seen.add(current.attemptId()) || seen.size() > 200)
                throw new SQLException("Invalid attempt source chain");
            if (!original.workspace().equals(current.workspace()) || !original.parentSessionId().equals(current.parentSessionId()) || !original.executionId().equals(current.executionId()) || !original.planId().equals(current.planId()) || !original.taskId().equals(current.taskId()))
                throw new SQLException("Invalid attempt source ownership");
            current = current.sourceAttemptId() == null ? null : find(c, current.sourceAttemptId()).orElseThrow(() -> new SQLException("Source attempt missing"));
        }
    }

    private TaskAttemptBinding map(ResultSet r) throws SQLException {
        return new TaskAttemptBinding(r.getString("workspace"), r.getString("parent_session_id"), r.getString("execution_id"), r.getString("plan_id"), r.getString("task_id"), r.getInt("attempt_no"), r.getString("attempt_id"), r.getString("child_session_id"), r.getString("runtime_owner_id"), r.getInt("runtime_attempt"), r.getString("source_attempt_id"), r.getLong("created_at"));
    }

    public synchronized void recordStarted(TaskAttemptBinding a, String call, int ordinal, String tool, long sequence, String hash) throws SQLException {
        requireExact(a);
        if (sequence < 0 || hash == null || hash.isBlank())
            throw new SQLException("Start source required");
        try (Connection c = write()) {
            checkFormat(c, true);
            try (var s = c.prepareStatement("INSERT INTO task_recovery_invocations(attempt_id,call_id,call_ordinal,tool_name,start_sequence,start_hash,observed_at) VALUES(?,?,?,?,?,?,?)")) {
                s.setString(1, a.attemptId());
                s.setString(2, call);
                s.setInt(3, ordinal);
                s.setString(4, tool);
                s.setLong(5, sequence);
                s.setString(6, hash);
                s.setLong(7, System.currentTimeMillis());
                s.executeUpdate();
            }
        }
    }

    public synchronized void recordResult(TaskAttemptBinding a, String call, int ordinal, String tool, boolean successful, boolean timeout, Integer exitCode, long sequence, String hash) throws SQLException {
        requireExact(a);
        if (sequence < 0 || hash == null || hash.isBlank())
            throw new SQLException("Result source required");
        try (Connection c = write()) {
            checkFormat(c, true);
            try (var s = c.prepareStatement("UPDATE task_recovery_invocations SET result_sequence=?,result_hash=?,successful=?,timed_out=?,exit_code=?,observed_at=? WHERE attempt_id=? AND call_id=? AND call_ordinal=? AND tool_name=? AND result_sequence IS NULL AND start_sequence<?")) {
                s.setLong(1, sequence);
                s.setString(2, hash);
                s.setInt(3, successful ? 1 : 0);
                s.setInt(4, timeout ? 1 : 0);
                if (exitCode == null)
                    s.setNull(5, Types.INTEGER);
                else
                    s.setInt(5, exitCode);
                s.setLong(6, System.currentTimeMillis());
                s.setString(7, a.attemptId());
                s.setString(8, call);
                s.setInt(9, ordinal);
                s.setString(10, tool);
                s.setLong(11, sequence);
                if (s.executeUpdate() != 1)
                    throw new SQLException("Result start reference missing");
            }
        }
    }

    /**
     * Rebuild from typed durable lifecycle, including starts newer than the last checkpoint.
     */
    public TaskRecoveryCheckpoint readCheckpoint(TaskAttemptBinding a) throws SQLException {
        requireExact(a);
        try (Connection c = read()) {
            checkFormat(c, true);
            return rebuild(c, a);
        }
    }

    private TaskRecoveryCheckpoint rebuild(Connection c, TaskAttemptBinding a) throws SQLException {
        var facts = new ArrayList<RecoveryFact>();
        var unknowns = new ArrayList<RecoveryUnknown>();
        long last = 0;
        try (var s = c.prepareStatement("SELECT * FROM task_recovery_invocations WHERE attempt_id=? ORDER BY start_sequence,call_ordinal")) {
            s.setString(1, a.attemptId());
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    if (facts.size() + unknowns.size() == 200)
                        throw new SQLException("Checkpoint fact limit exceeded");
                    String call = r.getString("call_id"), tool = r.getString("tool_name");
                    long start = r.getLong("start_sequence");
                    last = Math.max(last, start);
                    long result = r.getLong("result_sequence");
                    boolean missing = r.wasNull();
                    if (missing) {
                        unknowns.add(new RecoveryUnknown(RecoveryUnknown.Kind.INVOCATION_OUTCOME_UNKNOWN, a.taskId(), call, "Invocation started; no durable outcome", "Verify effect before retry"));
                        continue;
                    }
                    if (result <= start)
                        throw new SQLException("Checkpoint source order invalid");
                    last = Math.max(last, result);
                    boolean timeout = r.getInt("timed_out") != 0, ok = r.getInt("successful") != 0;
                    Integer exit = (Integer) r.getObject("exit_code");
                    RecoveryFact.Kind kind = "execute_command".equals(tool) ? timeout || exit == null ? RecoveryFact.Kind.COMMAND_OUTCOME_UNKNOWN : ok && exit == 0 ? RecoveryFact.Kind.COMMAND_SUCCEEDED : RecoveryFact.Kind.COMMAND_FAILED : ok ? RecoveryFact.Kind.TOOL_SUCCEEDED : RecoveryFact.Kind.TOOL_FAILED;
                    if (timeout || ("execute_command".equals(tool) && exit == null))
                        unknowns.add(new RecoveryUnknown(RecoveryUnknown.Kind.INVOCATION_OUTCOME_UNKNOWN, a.taskId(), call, "Command outcome not verified", "Verify current effect"));
                    facts.add(new RecoveryFact(call, kind, kind.name(), r.getLong("observed_at"), RecoveryFact.VerificationClass.HISTORICAL, List.of(new RecoverySourceRef("child", a.childSessionId(), result, a.attemptId(), r.getString("result_hash")))));
                }
            }
        }
        String id = null;
        int number = 0;
        try (var s = c.prepareStatement("SELECT * FROM task_recovery_checkpoints WHERE attempt_id=? ORDER BY checkpoint_no DESC LIMIT 1")) {
            s.setString(1, a.attemptId());
            try (var r = s.executeQuery()) {
                if (r.next()) {
                    id = r.getString("checkpoint_id");
                    number = r.getInt("checkpoint_no");
                    long prefix = r.getLong("child_last_sequence");
                    if (prefix < 0 || number < 1)
                        throw new SQLException("Checkpoint metadata invalid");
                    try {
                        List<RecoveryFact> recordedFacts = JSON.readValue(r.getString("facts_json"), new TypeReference<>() {
                        });
                        List<RecoveryUnknown> recordedUnknowns = JSON.readValue(r.getString("unknowns_json"), new TypeReference<>() {
                        });
                        if (recordedFacts.size() > 200 || recordedUnknowns.size() > 200)
                            throw new IllegalArgumentException();
                        for (var fact : recordedFacts) {
                            if (fact.verificationClass() != RecoveryFact.VerificationClass.HISTORICAL || fact.sourceRefs().isEmpty() || facts.stream().noneMatch(fact::equals))
                                throw new IllegalArgumentException();
                            for (var ref : fact.sourceRefs()) if (!a.attemptId().equals(ref.attemptId()) || !a.childSessionId().equals(ref.recordId()) || ref.sequence() == null || ref.sequence() > prefix)
                                throw new IllegalArgumentException();
                        }
                        for (var unknown : recordedUnknowns) if (!a.taskId().equals(unknown.taskId()))
                            throw new IllegalArgumentException();
                        if (!JSON.readTree(r.getString("evidence_refs_json")).isArray() || !JSON.readTree(r.getString("summary_hints_json")).isArray())
                            throw new IllegalArgumentException();
                    } catch (Exception failure) {
                        throw new SQLException("Checkpoint content invalid");
                    }
                    last = Math.max(last, prefix);
                }
            }
        }
        return new TaskRecoveryCheckpoint(id, a.attemptId(), number, last, facts, unknowns);
    }

    /**
     * Fixed typed start/result references for independent child prefix validation.
     */
    public List<RecoverySourceRef> readInvocationSources(TaskAttemptBinding a) throws SQLException {
        requireExact(a);
        var refs = new ArrayList<RecoverySourceRef>();
        try (Connection c = read()) {
            checkFormat(c, true);
            try (var s = c.prepareStatement("SELECT start_sequence,start_hash,result_sequence,result_hash FROM task_recovery_invocations WHERE attempt_id=? ORDER BY start_sequence")) {
                s.setString(1, a.attemptId());
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        if (refs.size() >= 400)
                            throw new SQLException("Invocation source limit exceeded");
                        refs.add(new RecoverySourceRef("child", a.childSessionId(), r.getLong(1), a.attemptId(), r.getString(2)));
                        long result = r.getLong(3);
                        if (!r.wasNull())
                            refs.add(new RecoverySourceRef("child", a.childSessionId(), result, a.attemptId(), r.getString(4)));
                    }
                }
            }
        }
        return List.copyOf(refs);
    }

    public synchronized TaskRecoveryCheckpoint checkpoint(TaskAttemptBinding a, long committedSequence, String phase) throws SQLException {
        requireExact(a);
        try (Connection c = write()) {
            checkFormat(c, true);
            c.setAutoCommit(false);
            try {
                var state = rebuild(c, a);
                if (state.childLastSequence() > committedSequence)
                    throw new SQLException("Checkpoint prefix unavailable");
                String id = "checkpoint_" + UUID.randomUUID();
                int n = state.checkpointNo() + 1;
                try (var s = c.prepareStatement("INSERT INTO task_recovery_checkpoints VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    s.setString(1, id);
                    s.setString(2, a.attemptId());
                    s.setInt(3, n);
                    s.setLong(4, committedSequence);
                    s.setLong(5, System.currentTimeMillis());
                    s.setString(6, phase);
                    s.setString(7, JSON.writeValueAsString(state.facts()));
                    s.setString(8, JSON.writeValueAsString(state.unknowns()));
                    s.setString(9, "[]");
                    s.setString(10, "[]");
                    s.executeUpdate();
                }
                try (var s = c.prepareStatement("UPDATE task_execution_attempts SET last_checkpoint_id=?,updated_at=? WHERE attempt_id=?")) {
                    s.setString(1, id);
                    s.setLong(2, System.currentTimeMillis());
                    s.setString(3, a.attemptId());
                    s.executeUpdate();
                }
                c.commit();
                return new TaskRecoveryCheckpoint(id, a.attemptId(), n, committedSequence, state.facts(), state.unknowns());
            } catch (Exception e) {
                c.rollback();
                throw new SQLException("Checkpoint write failed");
            }
        }
    }
}
