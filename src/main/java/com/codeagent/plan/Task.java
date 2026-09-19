package com.codeagent.plan;

import java.util.*;

/**
 * 任务节点 - 表示一个可执行的任务单元
 */
public class Task {
    private final String id;
    private final String description;
    private final TaskType type;
    private volatile TaskStatus status;
    private volatile String result;
    private volatile String error;
    private final TaskResourceClaims resourceClaims;
    private final List<String> acceptanceCriteria;
    private final Set<EvidenceType> requiredEvidence;
    private final List<String> dependencies;  // 依赖的其他任务ID
    private final List<String> dependents;    // 依赖此任务的其他任务ID
    private volatile long startTime;
    private volatile long endTime;

    public enum TaskType {
        PLANNING,      // 规划任务
        FILE_READ,     // 读取文件
        FILE_WRITE,    // 写入文件
        COMMAND,       // 执行命令
        ANALYSIS,      // 分析结果
        VERIFICATION   // 验证结果
    }

    public enum TaskStatus {
        REVIEWING,
        UNVERIFIED,
        PENDING,       // 等待执行
        RUNNING,       // 执行中
        COMPLETED,     // 已完成
        FAILED,        // 失败
        SKIPPED        // 跳过
    }

    public Task(String id, String description, TaskType type) {
        this(id, description, type, List.of(), TaskResourceClaims.conservativeDefault(type),
                List.of(), Set.of());
    }

    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this(id, description, type, dependencies, TaskResourceClaims.conservativeDefault(type),
                List.of(), Set.of());
    }

    public Task(String id,
                String description,
                TaskType type,
                List<String> dependencies,
                TaskResourceClaims resourceClaims,
                List<String> acceptanceCriteria,
                Set<EvidenceType> requiredEvidence) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.dependencies = new ArrayList<>(dependencies == null ? List.of() : dependencies);
        this.dependents = new ArrayList<>();
        this.resourceClaims = resourceClaims == null
                ? TaskResourceClaims.conservativeDefault(type)
                : resourceClaims;
        this.acceptanceCriteria = List.copyOf(acceptanceCriteria == null ? List.of() : acceptanceCriteria);
        this.requiredEvidence = Collections.unmodifiableSet(new LinkedHashSet<>(
                requiredEvidence == null ? Set.of() : requiredEvidence));
    }

    // Getters
    public String getId() { return id; }
    public String getDescription() { return description; }
    public TaskType getType() { return type; }
    public TaskStatus getStatus() { return status; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public List<String> getDependencies() { return new ArrayList<>(dependencies); }
    public List<String> getDependents() { return new ArrayList<>(dependents); }
    public TaskResourceClaims getResourceClaims() { return resourceClaims; }
    public List<String> getAcceptanceCriteria() { return acceptanceCriteria; }
    public Set<EvidenceType> getRequiredEvidence() { return requiredEvidence; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    // Setters
    public void setStatus(TaskStatus status) { this.status = status; }
    public void setResult(String result) { this.result = result; }
    public void setError(String error) { this.error = error; }

    public void addDependent(String taskId) {
        if (!dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    public void addDependency(String taskId) {
        if (!dependencies.contains(taskId)) {
            dependencies.add(taskId);
        }
    }

    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }

    public void markReviewing() {
        this.status = TaskStatus.REVIEWING;
    }

    public void markUnverified(String reason) {
        this.status = TaskStatus.UNVERIFIED;
        this.error = reason;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    public void markSkipped() {
        this.status = TaskStatus.SKIPPED;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 获取执行耗时（毫秒）
     */
    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    /**
     * 是否可以执行（所有依赖都已完成）
     */
    public boolean isExecutable(Map<String, Task> allTasks) {
        if (status != TaskStatus.PENDING) return false;
        for (String depId : dependencies) {
            Task dep = allTasks.get(depId);
            if (dep == null || dep.getStatus() != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("Task[%s: %s] (%s)", id, description, status);
    }
}
