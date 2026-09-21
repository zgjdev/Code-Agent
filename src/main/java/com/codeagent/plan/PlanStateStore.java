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
 * <p>Plan identity is bound to the durable parent Session. Prompt text is task
 * content, not recovery identity. Legacy prompt-bound rows remain readable at
 * the schema level but are never guessed into a Session.</p>
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
        migrateSchema();
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

    /**
     * Legacy/unbound save path retained only for callers that do not have a
     * durable parent Session. Such plans are deliberately not resumable.
     */
    public synchronized void savePlan(Path workspace, ExecutionPlan plan) throws SQLException {
        savePlan(workspace, null, "", plan);
    }

    public synchronized void savePlan(Path workspace, String sessionId, ExecutionPlan plan) throws SQLException {
        savePlan(workspace, sessionId, plan == null ? "" : plan.getGoal(), plan);
    }

    /**
     * Persists a Plan and binds it to one durable parent Session. policyInput is
     * stored only to reconstruct the original top-level authorization boundary;
     * it is never used as Plan identity.
     */
    public synchronized void savePlan(Path workspace,
                                      String sessionId,
                                      String policyInput,
                                      ExecutionPlan plan) throws SQLException {
        Objects.requireNonNull(plan, "plan");
        String normalizedSessionId = normalizeSessionId(sessionId);
        String now = Instant.now().toString();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO plan_runs (
                            id, workspace, session_id, resume_key, policy_input,
                            goal, status, summary, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET
                            workspace = excluded.workspace,
                            session_id = excluded.session_id,
                            policy_input = excluded.policy_input,
                            goal = excluded.goal,
                            status = excluded.status,
                            summary = excluded.summary,
                            updated_at = excluded.updated_at
                        """)) {
                    ps.setString(1, plan.getId());
                    ps.setString(2, normalizeWorkspace(workspace));
                    if (normalizedSessionId == null) {
                        ps.setNull(3, Types.VARCHAR);
                    } else {
                        ps.setString(3, normalizedSessionId);
                    }
                    // Keep the old NOT NULL column populated for schema compatibility only.
                    ps.setString(4, "");
                    ps.setString(5, policyInput == null ? "" : policyInput);
                    ps.setString(6, plan.getGoal());
                    ps.setString(7, plan.getStatus().name());
                    ps.setString(8, plan.getSummary());
                    ps.setString(9, now);
                    ps.setString(10, now);
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

    /**
     * Read-only lookup used for status prompts and duplicate-plan prevention.
     * It must not reinterpret RUNNING tasks as interrupted.
     */
    public synchronized Optional<ActivePlanInfo> findActiveInfo(Path workspace, String sessionId)
            throws SQLException {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (normalizedSessionId == null) {
            return Optional.empty();
        }
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement("""
                     SELECT r.id, r.goal, r.status,
                            SUM(CASE WHEN t.status = ? THEN 1 ELSE 0 END) AS completed_tasks,
                            COUNT(t.task_id) AS total_tasks
                     FROM plan_runs r
                     LEFT JOIN plan_tasks t ON t.plan_id = r.id
                     WHERE r.workspace = ?
                       AND r.session_id = ?
                       AND r.status IN (?, ?)
                     GROUP BY r.id, r.goal, r.status, r.updated_at, r.created_at
                     ORDER BY r.updated_at DESC, r.created_at DESC
                     LIMIT 1
                     """)) {
            ps.setString(1, Task.TaskStatus.COMPLETED.name());
            ps.setString(2, normalizeWorkspace(workspace));
            ps.setString(3, normalizedSessionId);
            ps.setString(4, ExecutionPlan.PlanStatus.CREATED.name());
            ps.setString(5, ExecutionPlan.PlanStatus.RUNNING.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ActivePlanInfo(
                        rs.getString("id"),
                        rs.getString("goal"),
                        ExecutionPlan.PlanStatus.valueOf(rs.getString("status")),
                        rs.getInt("completed_tasks"),
                        rs.getInt("total_tasks")));
            }
        }
    }

    /**
     * Finds the active Plan owned by one Session. This is the only recovery lookup.
     */
    public synchronized Optional<ResumeCandidate> findActive(Path workspace, String sessionId) throws SQLException {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (normalizedSessionId == null) {
            return Optional.empty();
        }

        String workspaceKey = normalizeWorkspace(workspace);
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement("""
                     SELECT id, goal, status, summary, policy_input
                     FROM plan_runs
                     WHERE workspace = ?
                       AND session_id = ?
                       AND status IN (?, ?)
                     ORDER BY updated_at DESC, created_at DESC
                     LIMIT 1
                     """)) {
            ps.setString(1, workspaceKey);
            ps.setString(2, normalizedSessionId);
            ps.setString(3, ExecutionPlan.PlanStatus.CREATED.name());
            ps.setString(4, ExecutionPlan.PlanStatus.RUNNING.name());

            String planId;
            String persistedGoal;
            String persistedSummary;
            ExecutionPlan.PlanStatus persistedStatus;
            String policyInput;
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                planId = rs.getString("id");
                persistedGoal = rs.getString("goal");
                persistedSummary = rs.getString("summary");
                persistedStatus = ExecutionPlan.PlanStatus.valueOf(rs.getString("status"));
                policyInput = rs.getString("policy_input");
            }

            ExecutionPlan plan = new ExecutionPlan(planId, persistedGoal);
            plan.setSummary(persistedSummary);
            plan.setStatus(persistedStatus);
            Set<String> interruptedTaskIds = restoreTasks(connection, plan);
            if (!plan.computeExecutionOrder()) {
                throw new SQLException("持久化 Plan 存在循环依赖: " + planId);
            }
            persistInterruptedRecovery(connection, planId, interruptedTaskIds);
            return Optional.of(new ResumeCandidate(plan, interruptedTaskIds,
                    policyInput == null ? "" : policyInput));
        }
    }

    public synchronized boolean abandonActive(Path workspace, String sessionId) throws SQLException {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (normalizedSessionId == null) {
            return false;
        }
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement("""
                     UPDATE plan_runs
                     SET status = ?, updated_at = ?
                     WHERE workspace = ?
                       AND session_id = ?
                       AND status IN (?, ?)
                     """)) {
            ps.setString(1, ExecutionPlan.PlanStatus.CANCELLED.name());
            ps.setString(2, Instant.now().toString());
            ps.setString(3, normalizeWorkspace(workspace));
            ps.setString(4, normalizedSessionId);
            ps.setString(5, ExecutionPlan.PlanStatus.CREATED.name());
            ps.setString(6, ExecutionPlan.PlanStatus.RUNNING.name());
            return ps.executeUpdate() > 0;
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

    private static String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionId.trim();
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
                        session_id TEXT,
                        resume_key TEXT NOT NULL DEFAULT '',
                        policy_input TEXT NOT NULL DEFAULT '',
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
        }
    }

    private void migrateSchema() throws SQLException {
        try (Connection connection = openConnection()) {
            if (!hasColumn(connection, "plan_runs", "session_id")) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("ALTER TABLE plan_runs ADD COLUMN session_id TEXT");
                }
            }
            if (!hasColumn(connection, "plan_runs", "policy_input")) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("ALTER TABLE plan_runs ADD COLUMN policy_input TEXT NOT NULL DEFAULT ''");
                }
            }
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_plan_runs_session
                        ON plan_runs(workspace, session_id, status, updated_at)
                        """);
                stmt.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS idx_plan_runs_one_active_per_session
                        ON plan_runs(workspace, session_id)
                        WHERE session_id IS NOT NULL
                          AND status IN ('CREATED', 'RUNNING')
                        """);
            }
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
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

    public record ActivePlanInfo(String planId,
                                 String goal,
                                 ExecutionPlan.PlanStatus status,
                                 int completedTasks,
                                 int totalTasks) {
    }

    public record ResumeCandidate(ExecutionPlan plan,
                                  Set<String> interruptedTaskIds,
                                  String policyInput) {
        public ResumeCandidate {
            Objects.requireNonNull(plan, "plan");
            interruptedTaskIds = interruptedTaskIds == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(interruptedTaskIds));
            policyInput = policyInput == null ? "" : policyInput;
        }
    }
}
