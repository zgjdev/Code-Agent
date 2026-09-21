package com.codeagent.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * SQLite-backed checkpoint store for Plan-and-Execute DAG state.
 *
 * <p>The recovery boundary is a whole Task. Model/tool sub-steps remain recorded
 * by SessionStore/ConversationLedger, while this store owns the resumable DAG
 * structure and scheduling state.</p>
 */
public final class PlanStateStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path dbPath;

    public PlanStateStore(Path dbPath) throws SQLException {
        this.dbPath = Objects.requireNonNull(dbPath).toAbsolutePath().normalize();
        try {
            Path parent = this.dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                setPosixPermissionsIfSupported(parent, DIRECTORY_PERMISSIONS);
            }
        } catch (IOException e) {
            throw new SQLException("无法创建 Plan 状态目录: " + e.getMessage(), e);
        }
        initTables();
        setPosixPermissionsIfSupported(this.dbPath, FILE_PERMISSIONS);
    }

    public static PlanStateStore openDefault() throws SQLException {
        String configured = System.getProperty("codeagent.plan.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("CODEAGENT_PLAN_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home"), ".codeagent", "plans").toString();
        }
        return new PlanStateStore(Path.of(configured).resolve("plans.db"));
    }

    public Path dbPath() {
        return dbPath;
    }

    public synchronized void savePlan(Path workspace, ExecutionPlan plan) throws SQLException {
        savePlan(workspace, plan == null ? null : plan.getGoal(), plan);
    }

    public synchronized void savePlan(Path workspace, String resumeKey, ExecutionPlan plan) throws SQLException {
        Objects.requireNonNull(plan, "plan");
        String now = Instant.now().toString();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO plan_runs (
                            id, workspace, resume_key, goal, status, summary, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET
                            workspace = excluded.workspace,
                            resume_key = excluded.resume_key,
                            goal = excluded.goal,
                            status = excluded.status,
                            summary = excluded.summary,
                            updated_at = excluded.updated_at
                        """)) {
                    ps.setString(1, plan.getId());
                    ps.setString(2, normalizeWorkspace(workspace));
                    ps.setString(3, resumeKey == null ? "" : resumeKey);
                    ps.setString(4, plan.getGoal());
                    ps.setString(5, plan.getStatus().name());
                    ps.setString(6, plan.getSummary());
                    ps.setString(7, now);
                    ps.setString(8, now);
                    ps.executeUpdate();
                }

                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM plan_tasks WHERE plan_id = ?")) {
                    delete.setString(1, plan.getId());
                    delete.executeUpdate();
                }

                int ordinal = 0;
                for (String taskId : plan.getExecutionOrder()) {
                    Task task = plan.getTask(taskId);
                    insertTask(connection, plan.getId(), ordinal++, task, now);
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("保存 Plan 状态失败: " + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public synchronized void checkpointPlan(ExecutionPlan plan) throws SQLException {
        Objects.requireNonNull(plan, "plan");
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement("""
                     UPDATE plan_runs
                     SET status = ?, summary = ?, updated_at = ?
                     WHERE id = ?
                     """)) {
            ps.setString(1, plan.getStatus().name());
            ps.setString(2, plan.getSummary());
            ps.setString(3, Instant.now().toString());
            ps.setString(4, plan.getId());
            ps.executeUpdate();
        }
    }

    public synchronized void checkpointTask(String planId, Task task) throws SQLException {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(task, "task");
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement("""
                     UPDATE plan_tasks
                     SET status = ?, result = ?, error = ?, updated_at = ?
                     WHERE plan_id = ? AND task_id = ?
                     """)) {
            ps.setString(1, task.getStatus().name());
            ps.setString(2, task.getResult());
            ps.setString(3, task.getError());
            ps.setString(4, Instant.now().toString());
            ps.setString(5, planId);
            ps.setString(6, task.getId());
            ps.executeUpdate();
        }
    }

    public synchronized Optional<ResumeCandidate> findResumable(Path workspace, String resumeKey) throws SQLException {
        String workspaceKey = normalizeWorkspace(workspace);
        String exactResumeKey = resumeKey == null ? "" : resumeKey;
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement("""
                     SELECT id, goal, status, summary
                     FROM plan_runs
                     WHERE workspace = ?
                       AND resume_key = ?
                       AND status IN (?, ?)
                     ORDER BY updated_at DESC, created_at DESC
                     LIMIT 1
                     """)) {
            ps.setString(1, workspaceKey);
            ps.setString(2, exactResumeKey);
            ps.setString(3, ExecutionPlan.PlanStatus.CREATED.name());
            ps.setString(4, ExecutionPlan.PlanStatus.RUNNING.name());
            String planId;
            String persistedGoal;
            String persistedSummary;
            ExecutionPlan.PlanStatus persistedStatus;
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                planId = rs.getString("id");
                persistedGoal = rs.getString("goal");
                persistedSummary = rs.getString("summary");
                persistedStatus = ExecutionPlan.PlanStatus.valueOf(rs.getString("status"));
            }

            ExecutionPlan plan = new ExecutionPlan(planId, persistedGoal);
            plan.setSummary(persistedSummary);
            plan.setStatus(persistedStatus);
            Set<String> interruptedTaskIds = restoreTasks(connection, plan);
            if (!plan.computeExecutionOrder()) {
                throw new SQLException("持久化 Plan 存在循环依赖: " + planId);
            }
            persistInterruptedRecovery(connection, planId, interruptedTaskIds);
            return Optional.of(new ResumeCandidate(plan, interruptedTaskIds));
        }
    }

    private Set<String> restoreTasks(Connection connection, ExecutionPlan plan) throws SQLException {
        Set<String> interrupted = new LinkedHashSet<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT task_id, description, type, status, dependencies_json,
                       read_paths_json, write_paths_json, workspace_write,
                       acceptance_criteria_json, required_evidence_json,
                       result, error
                FROM plan_tasks
                WHERE plan_id = ?
                ORDER BY ordinal ASC
                """)) {
            ps.setString(1, plan.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Task.TaskStatus persistedStatus = Task.TaskStatus.valueOf(rs.getString("status"));
                    Task task = new Task(
                            rs.getString("task_id"),
                            rs.getString("description"),
                            Task.TaskType.valueOf(rs.getString("type")),
                            readStringList(rs.getString("dependencies_json")),
                            new TaskResourceClaims(
                                    readStringList(rs.getString("read_paths_json")),
                                    readStringList(rs.getString("write_paths_json")),
                                    rs.getInt("workspace_write") != 0),
                            readStringList(rs.getString("acceptance_criteria_json")),
                            readEvidenceSet(rs.getString("required_evidence_json")));

                    restoreTaskState(task, persistedStatus, rs.getString("result"), rs.getString("error"),
                            interrupted);
                    plan.addTask(task);
                }
            }
        }
        return Collections.unmodifiableSet(interrupted);
    }

    private void restoreTaskState(Task task,
                                  Task.TaskStatus status,
                                  String result,
                                  String error,
                                  Set<String> interrupted) {
        switch (status) {
            case PENDING -> {
                // Constructor default.
            }
            case INTERRUPTED -> {
                task.markInterrupted(error);
                interrupted.add(task.getId());
            }
            case RUNNING, REVIEWING -> {
                task.markInterrupted("上次执行在进程退出时中断");
                interrupted.add(task.getId());
            }
            case COMPLETED -> task.markCompleted(result);
            case UNVERIFIED -> task.markUnverified(error);
            case FAILED -> task.markFailed(error);
            case SKIPPED -> task.markSkipped();
        }
    }

    private void persistInterruptedRecovery(Connection connection,
                                            String planId,
                                            Set<String> interruptedTaskIds) throws SQLException {
        if (interruptedTaskIds.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE plan_tasks
                SET status = ?, error = ?, updated_at = ?
                WHERE plan_id = ? AND task_id = ?
                """)) {
            String now = Instant.now().toString();
            for (String taskId : interruptedTaskIds) {
                ps.setString(1, Task.TaskStatus.INTERRUPTED.name());
                ps.setString(2, "上次执行在进程退出时中断");
                ps.setString(3, now);
                ps.setString(4, planId);
                ps.setString(5, taskId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertTask(Connection connection,
                            String planId,
                            int ordinal,
                            Task task,
                            String now) throws SQLException, IOException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO plan_tasks (
                    plan_id, task_id, ordinal, description, type, status,
                    dependencies_json, read_paths_json, write_paths_json, workspace_write,
                    acceptance_criteria_json, required_evidence_json,
                    result, error, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, planId);
            ps.setString(2, task.getId());
            ps.setInt(3, ordinal);
            ps.setString(4, task.getDescription());
            ps.setString(5, task.getType().name());
            ps.setString(6, task.getStatus().name());
            ps.setString(7, writeJson(task.getDependencies()));
            ps.setString(8, writeJson(task.getResourceClaims().readPaths()));
            ps.setString(9, writeJson(task.getResourceClaims().writePaths()));
            ps.setInt(10, task.getResourceClaims().workspaceWrite() ? 1 : 0);
            ps.setString(11, writeJson(task.getAcceptanceCriteria()));
            ps.setString(12, writeJson(task.getRequiredEvidence().stream().map(Enum::name).toList()));
            ps.setString(13, task.getResult());
            ps.setString(14, task.getError());
            ps.setString(15, now);
            ps.executeUpdate();
        }
    }

    private List<String> readStringList(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(JSON.readValue(json, STRING_LIST));
        } catch (IOException e) {
            throw new SQLException("读取 Plan JSON 字段失败", e);
        }
    }

    private Set<EvidenceType> readEvidenceSet(String json) throws SQLException {
        LinkedHashSet<EvidenceType> evidence = new LinkedHashSet<>();
        for (String value : readStringList(json)) {
            evidence.add(EvidenceType.valueOf(value));
        }
        return Collections.unmodifiableSet(evidence);
    }

    private String writeJson(Object value) throws IOException {
        return JSON.writeValueAsString(value);
    }

    private String normalizeWorkspace(Path workspace) {
        Path base = workspace == null ? Path.of(".") : workspace;
        return base.toAbsolutePath().normalize().toString();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    private void initTables() throws SQLException {
        try (Connection connection = openConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS plan_runs (
                        id TEXT PRIMARY KEY,
                        workspace TEXT NOT NULL,
                        resume_key TEXT NOT NULL,
                        goal TEXT NOT NULL,
                        status TEXT NOT NULL,
                        summary TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS plan_tasks (
                        plan_id TEXT NOT NULL,
                        task_id TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        description TEXT NOT NULL,
                        type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        dependencies_json TEXT NOT NULL,
                        read_paths_json TEXT NOT NULL,
                        write_paths_json TEXT NOT NULL,
                        workspace_write INTEGER NOT NULL,
                        acceptance_criteria_json TEXT NOT NULL,
                        required_evidence_json TEXT NOT NULL,
                        result TEXT,
                        error TEXT,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(plan_id, task_id),
                        FOREIGN KEY(plan_id) REFERENCES plan_runs(id) ON DELETE CASCADE
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_plan_runs_resume
                    ON plan_runs(workspace, resume_key, status, updated_at)
                    """);
        }
    }

    private static void setPosixPermissionsIfSupported(Path path,
                                                       Set<PosixFilePermission> permissions) {
        try {
            if (path != null && Files.exists(path)) {
                Files.setPosixFilePermissions(path, permissions);
            }
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            // Windows and restricted filesystems do not expose POSIX permissions.
        }
    }

    public record ResumeCandidate(ExecutionPlan plan, Set<String> interruptedTaskIds) {
        public ResumeCandidate {
            Objects.requireNonNull(plan, "plan");
            interruptedTaskIds = interruptedTaskIds == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(interruptedTaskIds));
        }
    }
}
