# Plan DIFF Evidence 真实工作区差异校验

> 状态：第 1–10 节保留此前 DIFF evidence 改造的已验证历史；第 11 节记录本次 Reviewer 实际 patch 与命令证据扩展，当前正在完成回归与独立审查。
>
> 本文是本次“将 Plan 的 DIFF evidence 从 write_file 成功标记升级为真实 before/after 工作区差异”改造的独立设计、实施与审计文档。后续审查本次修改时，应以本文结合源码和提交记录为准。
>
> 基线：`main@5a0bedbbd695d1ed4cefe5c39cc724b898cc9ff5`
>
> 功能分支：`feat/plan-diff-evidence-jgit`

## 1. 背景、目标与非目标

### 1.1 背景

现有 Plan-and-Execute 已支持 `EvidenceType.DIFF`，但改造前的实际实现并没有进行真正的代码差异计算。

改造前 `TaskEvidenceCollector.observeTools(...)` 的语义是：

```text
观察到 write_file ToolExecutionResult
        ↓
successful = true
        ↓
直接生成 DIFF / PASSED
```

因此旧语义实际只能证明：

> 某次 `write_file` 调用被工具层报告为成功。

它不能证明：

- 文件最终内容相对 Task 开始前真的发生变化；
- 写入是否只是把同样的内容重新覆盖了一次；
- 后续重试是否把前一次改动撤销；
- `execute_command` 等非 `write_file` 工具是否间接修改了工作区；
- 最终工作区的真实差异范围和规模。

这会导致 `DIFF` 这个证据名称比实际语义更强，确定性门禁可能将“写调用成功”误当成“存在真实差异”。

### 1.2 目标

本次改造目标：

1. 将 `DIFF` evidence 的事实来源从“成功的 `write_file` 调用”改为“Task 初始基线与当前工作区的真实 before/after 差异”。
2. 复用项目已存在的 JGit 依赖，不自行实现文本 diff 算法。
3. 使用 JGit `HistogramDiff` 计算文本文件的行级新增/删除统计。
4. 为并行 Plan Task 限定 diff scope，避免一个 Task 把另一个并行 Task 的修改计入自己的证据。
5. 对 `workspaceWrite=true` 的独占 Task 允许扫描整个项目工作区，同时沿用 Side-Git 的 exclude 规则。
6. DIFF evidence 只保存有界摘要、相关路径和摘要哈希，不保存源码正文或完整 diff。
7. 每次 evidence retry / Reviewer retry 后重新基于 Task 初始 baseline 计算累计 diff，旧的 DIFF evidence 必须被替换，不能残留 stale PASSED。
8. 保持现有 `DeterministicEvidenceGate`、Reviewer、DAG 状态机和权限链语义不变，只提升 DIFF 证据真实性。

### 1.3 非目标

本次不做：

- 不改变 Planner 的 `EvidenceType` 枚举或计划 JSON 契约。
- 不新增系统 Git 依赖；继续使用项目现有 JGit。
- 不把完整 unified diff 写入 Session ledger、AuditLog 或 Reviewer 请求。
- 不实现 AST 级语义 diff。
- 不把文件 diff 结果作为新的模型工具。
- 不修改 `ToolRegistry` 的 `write_file` 返回协议。
- 不改变冲突感知调度算法。
- 不尝试给并行 Task 扫描整个共享 workspace。
- 不改变 Side-Git 的 snapshot / restore 语义。

## 2. 现状与源码证据

### 2.1 改造前证据链

改造前：

```mermaid
flowchart LR
    A[LLM 调用 write_file] --> B[ToolRegistry 执行]
    B --> C{successful?}
    C -- true --> D[TaskEvidenceCollector]
    D --> E[DIFF / PASSED]
    E --> F[DeterministicEvidenceGate]
```

核心问题：`D → E` 没有读取或比较真实文件状态。

旧实现还会把同一次 `write_file` 同时记录为：

- `DIFF`
- `TOOL_RESULT`

其中 `DIFF` 只是由 Tool 调用状态推导，并非 workspace fact。

### 2.2 可复用基础设施

仓库已经包含：

```xml
org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r
```

Side-Git 使用 JGit 维护独立于用户系统 Git 的工作区快照。

因此本次不引入新的 diff 依赖，而是复用 JGit：

- `RawText`
- `RawTextComparator.DEFAULT`
- `HistogramDiff`
- `EditList`
- `Edit`

### 2.3 并行约束

Plan 当前支持多个无冲突 Task 最多 4 路并行。

如果每个 Task 都简单执行：

```text
Task start → snapshot entire workspace
Task end   → diff entire workspace
```

那么两个并行 Task A/B 即使写不同文件，也可能出现：

```text
A baseline
B baseline
A 修改 A.java
B 修改 B.java
A diff → A.java + B.java   ×
B diff → A.java + B.java   ×
```

这会污染证据归属。

因此 DIFF scope 必须与现有资源声明一致：

- 普通写 Task：只观察其 `writePaths`；
- `workspaceWrite=true`：整个 workspace，但该 Task 由冲突调度保证独占。

## 3. 方案设计

### 3.1 总体数据流

```mermaid
sequenceDiagram
    participant S as Plan Scheduler
    participant D as TaskWorkspaceDiffTracker
    participant W as Task Worker
    participant E as TaskEvidenceCollector
    participant G as DeterministicEvidenceGate
    participant R as Reviewer

    S->>D: Task 开始，按 ResourceClaims 捕获 baseline
    S->>W: 执行 Task
    loop ReAct / retry
        W->>W: read/write/command/...
    end
    W-->>S: 本轮 execution result
    S->>D: 比较当前 workspace 与初始 baseline
    D-->>E: DiffSummary
    E->>E: 替换旧 DIFF evidence
    E->>G: evidence snapshot
    alt DIFF 存在且其它必需证据通过
        G-->>R: 进入语义审核
    else DIFF 缺失或计算失败
        G-->>S: REJECTED，反馈 Worker 重试
    end
```

### 3.2 新组件：TaskWorkspaceDiffTracker

新增：

```text
src/main/java/com/codeagent/agent/TaskWorkspaceDiffTracker.java
```

职责：

1. Task 启动时捕获一次 baseline。
2. baseline 生命周期覆盖整个 Task，包括 evidence retry 和 Reviewer retry。
3. 调用 `diff()` 时重新读取当前作用域文件。
4. 比较 baseline/current，返回结构化 `DiffSummary`。

核心输出：

```java
public record DiffSummary(
    boolean changed,
    int changedFiles,
    int additions,
    int deletions,
    int lineStatsUnavailableFiles,
    List<String> changedPaths,
    String digest
) {}
```

### 3.3 Diff scope

#### 普通声明写集

当：

```text
workspaceWrite = false
writePaths = [...]
```

tracker 只捕获 `writePaths`。

支持：

- 单文件 claim；
- 目录 claim；
- 新增文件；
- 删除文件；
- 修改文件。

不会观察 `writePaths` 外的变化。

这既降低扫描成本，也隔离同批并行 Task 的证据。

#### workspaceWrite

当：

```text
workspaceWrite = true
```

tracker 捕获整个 project root。

由于现有 `ConflictAwareBatchSelector` 会让 workspaceWrite Task 独占批次，因此不存在并行 Task 证据串扰。

全工作区扫描沿用 `SnapshotConfig.fromEnvironment().excludes()` 的排除列表，例如：

- `.git`
- `.codeagent/snapshots`
- `target`
- `node_modules`
- `dist`
- `.idea`
- `*.class`
- `*.jar`

### 3.4 文件快照模型

tracker 不创建额外 Git commit；durable Plan 仅把 source-free 的 path/hash baseline 保存到状态库。

每个观察文件只在 Task 内存中保存：

```text
relativePath
sha256
可选 text bytes
textComparable
```

规则：

- 所有文件都通过 SHA-256 判断内容是否真正变化；
- 小于等于 2 MiB 且不含 NUL 字节的文件可进行行级文本 diff；
- 进程内 baseline 文本缓存设置 16 MiB 总上限，超过预算的文件仍保留 hash，但不提供行级统计；
- durable Plan 把 path/hash baseline 写入 `plan_tasks.diff_baseline_json`，不持久化源码 bytes；恢复后可证明内容变化，但由于没有原文，恢复任务的既有变化计入 `lineStatsUnavailableFiles` 而不伪造行数；
- 大文件仍计算流式 SHA-256，但不保留全文；
- 二进制文件只记录 changed file，不计算行数；
- symlink 本身不按普通文件递归跟随，避免 scope 逃逸。

### 3.5 JGit HistogramDiff

对于可比较文本：

```java
EditList edits = new HistogramDiff().diff(
    RawTextComparator.DEFAULT,
    beforeText,
    afterText
);
```

每个 `Edit`：

```text
deletions += endA - beginA
additions += endB - beginB
```

因此类似：

```text
before:
a
b
c

after:
a
x
b
c
```

结果应为：

```text
+1 / -0
```

而不是按固定行号错误地认为后续所有行都发生替换。

### 3.6 摘要哈希

DIFF evidence 不保存完整 diff 正文。

对每个真实变化路径按稳定顺序加入：

```text
path
before hash / <missing>
after hash / <missing>
```

计算一个整体 SHA-256 digest。

Evidence 摘要只展示短前缀，例如：

```text
files=2, +18, -6, hash=0123456789ab
```

完整 digest 保留在 `DiffSummary` 内存对象中，但 `TaskEvidence.summary` 保持有界。

### 3.7 TaskEvidenceCollector 语义调整

删除旧逻辑：

```text
write_file successful → DIFF/PASSED
```

保留：

```text
write_file successful → TOOL_RESULT/PASSED
```

新增：

```java
observeDiff(DiffSummary)
observeDiffFailure(String)
```

`observeDiff` 每次执行前先：

```text
removeIf(type == DIFF)
```

再写入最新 DIFF evidence。

这是必要的，因为 Task retry 使用同一个初始 baseline。

例如：

```text
baseline = A
retry 1: A → B      => DIFF PASSED
retry 2: B → A      => 当前最终状态重新等于 baseline
```

如果 append 而不是 replace：

```text
PASSED + FAILED
```

`DeterministicEvidenceGate` 当前只要找到任意 PASSED 就会放行，形成 stale evidence bug。

因此必须保证同一 Task 任意时刻最多一条当前 DIFF evidence。

### 3.8 PlanExecuteAgent 接线

Task 创建 `TaskEvidenceCollector` 后，如果该 Task 声明：

```text
requiredEvidence contains DIFF
```

则同时创建：

```text
TaskWorkspaceDiffTracker
```

baseline 创建失败时：

```text
DIFF / FAILED
source = workspace_diff
summary = baseline capture failed ...
```

每次进入 `DeterministicEvidenceGate` 前统一走：

```text
evaluateEvidence(...)
    ↓
diffTracker.diff()
    ↓
evidenceCollector.observeDiff(...)
    ↓
DeterministicEvidenceGate.evaluate(...)
```

适用位置：

1. 首次 Task 执行完成；
2. 确定性 evidence retry 后；
3. Reviewer 反馈重试后。

因此所有重试均基于同一个 Task 初始 baseline 重新观察“最终累计差异”。

### 3.9 失败语义

| 场景 | DIFF Evidence |
|---|---|
| scope 内发生真实文本修改 | PASSED |
| 新增文件 | PASSED |
| 删除文件 | PASSED |
| 二进制/大文件内容 hash 变化 | PASSED，行统计不计入 |
| write_file 成功但内容与 baseline 相同 | FAILED |
| 修改发生在当前 Task writePaths 外 | 不计入 |
| baseline 捕获失败 | FAILED |
| 当前 workspace 读取/diff 失败 | FAILED |
| retry 最终撤销所有变化 | 最新 evidence 为 FAILED |

DIFF 计算错误必须 fail-closed，不能回退到旧的 `write_file successful` 语义。

### 3.10 审查后加固设计

代码审查确认原实现还需要以下收敛：

1. **终态优先级**：存在 `UNVERIFIED` Task 时，Plan 最终展示必须保留“未验证”及其 blocking reason，不能落入通用“依赖未满足”提示。
2. **目录级排除**：`workspaceWrite` 使用 `walkFileTree`，在进入 `.git`、`target`、`node_modules` 等目录前直接 `SKIP_SUBTREE`，禁止先遍历再过滤。
3. **内存上限**：baseline 始终保留内容哈希，但进程内文本正文缓存设置 16 MiB 总上限；current 文本只在 hash 变化后按 2 MiB 单文件阈值临时读取，避免 baseline/current 同时无界常驻源码。持久化 baseline 只包含 path/hash。
4. **有界路径证据**：DIFF 保存完整 changed file 数量和 digest，但 `relatedPaths` 最多保留固定数量的稳定排序路径，并在摘要中标明省略数量。
5. **异常归一化**：文件树惰性遍历产生的 `UncheckedIOException` 必须在 tracker 边界转回 `IOException`，由 evidence collector 记录为 `DIFF/FAILED`。
6. **恢复基线**：durable Plan 在 Task 首次执行前将仅含相对路径和 SHA-256 的 baseline JSON 写入 `plans.db`；中断恢复必须读取该 baseline，不能以恢复后的 workspace 重新建基线。baseline 不保存源码正文。
7. **执行前硬门禁**：baseline 捕获、解析或持久化失败时，直接生成 `DIFF evidence failed` blocking reason；不得标记 Task 为 RUNNING，不得调用 Worker/LLM/工具，也不得进入执行失败重规划。

```mermaid
flowchart TD
    A[Task 首次执行] --> B[捕获 path + SHA-256 baseline]
    B --> C{durable Plan?}
    C -- 是 --> D[写入 plan_tasks.diff_baseline_json]
    C -- 否 --> E[仅保留内存 hash 与有界文本缓存]
    D -- 失败 --> X[UNVERIFIED: DIFF evidence failed]
    B -- 失败 --> X
    D --> F[执行 Worker]
    E --> F
    F --> G[按目录剪枝扫描当前状态]
    G --> H[hash 判定变化]
    H --> I[仅对变化且受阈值保护的文本临时做 HistogramDiff]
    I --> J[有界 paths + counts + digest]
    K[/plan resume] --> L[读取 durable baseline]
    L -- 缺失或损坏 --> X
    L --> G
```

## 4. 安全、并发、隐私与性能

### 4.1 权限边界

tracker 是只读观察器，不产生新的工具权限。

它不能：

- 扩大 `writePaths`；
- 授权 URL；
- 绕过 HITL；
- 绕过 PathGuard / CommandGuard；
- 修改 workspace；
- 触发新的 LLM Tool Call。

工具执行权限链仍保持现有行为。

### 4.2 并发一致性

`TaskWorkspaceDiffTracker` 为每个 Task 独立实例。

普通并行 Task 只观察自己的 `writePaths`，避免共享 workspace 的证据串扰。

`workspaceWrite=true` 依赖现有冲突调度保持批次独占；本次不新增第二套锁。

### 4.3 源码与敏感信息

不向：

- raw ledger
- AuditLog
- Planner
- Reviewer prompt

写入完整 before/after 源码。

`TaskEvidence` 仅携带：

- 变化路径；
- 文件数量；
- 新增/删除行统计；
- 无法提供行级统计的文件数量（binary、large、缓存预算外或 hash-only 恢复）；
- digest 短摘要。

### 4.4 性能边界

普通 Task 扫描范围由 `writePaths` 限制。

workspaceWrite 任务可能扫描整个项目，因此：

- 复用 Snapshot exclude；
- baseline 文本缓存总量最多 16 MiB，大文件不把完整 bytes 常驻内存；
- 只对 <= 2 MiB 的文本执行 HistogramDiff。

本次未引入全局缓存或增量文件 watcher，避免扩大实现范围。

## 5. 测试设计

### 5.1 TaskWorkspaceDiffTrackerTest

| 用例 | 预期 |
|---|---|
| 声明文件插入一行 | changed=true，+1/-0 |
| 声明目录内新增+删除文件 | 2 files，新增/删除统计正确 |
| writePaths 外文件同时变化 | 不进入 changedPaths |
| workspaceWrite 扫描 target 变化 | target 被 Side-Git excludes 忽略 |
| digest | 非空且基于稳定路径/内容哈希 |

### 5.2 TaskEvidenceCollectorTest

| 用例 | 预期 |
|---|---|
| 成功 write_file | 只有 TOOL_RESULT，不产生 DIFF |
| observeDiff(real change) | DIFF/PASSED，携带路径和统计 |
| PASSED 后观察 no-change | 旧 PASSED 被替换，只保留 FAILED |

### 5.3 PlanDiffEvidenceIntegrationTest

端到端构造：

```text
文件初始 = "same"
Task requiredEvidence = DIFF
LLM 连续调用 write_file 写回完全相同内容
```

预期：

1. `write_file` 调用本身成功；
2. workspace relative to baseline 没有真实变化；
3. DIFF evidence 为 FAILED；
4. deterministic gate 拒绝；
5. Worker 按既有上限重试；
6. 重试耗尽后 Task 进入未验证路径，而不是 COMPLETED。

该测试专门防止旧语义回归。

### 5.4 建议验证命令

完成实现后至少运行：

```bash
mvn test -DskipTests=false -Dtest=TaskWorkspaceDiffTrackerTest,TaskEvidenceCollectorTest,PlanDiffEvidenceIntegrationTest,DeterministicEvidenceGateTest
mvn test -DskipTests=false -Dtest=PlanExecuteAgentTest,ConflictAwareBatchSelectorTest
mvn test -Pquick
mvn test -DskipTests=false
mvn clean package
git diff --check
```

### 5.5 审查问题回归矩阵

| 问题 | 先失败的测试 | 验收结果 |
|---|---|---|
| UNVERIFIED 被展示为依赖阻塞 | `PlanDiffEvidenceIntegrationTest` | 返回文本包含“未验证”和 DIFF blocking reason |
| excludes 过滤过晚/嵌套目录漏排 | `TaskWorkspaceDiffTrackerTest` | `walkFileTree` 在目录入口剪枝；无斜杠规则匹配任意路径组件，路径 glob 匹配完整相对路径 |
| baseline/current 双份正文常驻 | `TaskWorkspaceDiffTrackerTest` | durable baseline 只序列化 path/hash；进程内 baseline 文本缓存有 16 MiB 总上限 |
| changedPaths 无上限 | `TaskEvidenceCollectorTest` | relatedPaths 有固定上限且摘要含省略数量 |
| 惰性遍历异常逃逸 | `TaskWorkspaceDiffTrackerTest` | 统一转为 `IOException` 并由 Plan 记录失败证据 |
| 恢复后重新建 baseline | `PlanExecuteRecoveryTest`、`PlanStateStoreTest` | 恢复使用首次执行前的持久化 hash baseline |
| baseline 初始化失败后仍执行 Worker | `PlanExecuteRecoveryTest`、`PlanDiffEvidenceIntegrationTest` | 形成明确的 DIFF blocking reason，且 Worker/LLM/工具调用次数为 0 |

实施顺序：先让上述测试在当前实现上稳定失败，再按“终态 → tracker → evidence 边界 → durable baseline → 文档”依赖顺序做最小修复；每个边界单独运行对应测试，最后执行 Plan 针对性回归、`mvn test -Pquick`、全量测试、构建和 `git diff --check`。

如果环境导致某条命令无法执行，必须记录真实失败原因，不得写成通过。

## 6. 实际实施记录

### 6.1 分支

```text
feat/plan-diff-evidence-jgit
```

基于：

```text
main@5a0bedbbd695d1ed4cefe5c39cc724b898cc9ff5
```

### 6.2 已产生提交

#### 1. `144db492ab1cdf8251f0890a5d5dc4bdc6956120`

```text
feat(plan): add task workspace diff tracker
```

内容：

- 新增 `TaskWorkspaceDiffTracker`；
- 新增 `TaskWorkspaceDiffTrackerTest`；
- JGit HistogramDiff；
- task scope / workspace scope；
- Side-Git excludes；
- hash / text/binary/large-file 处理。

#### 2. `3f1345acc10f2ffba3e185026bc67d16ae823e03`

```text
feat(plan): verify DIFF evidence from workspace changes
```

内容：

- 移除 `write_file successful → DIFF`；
- `TaskEvidenceCollector` 接收真实 `DiffSummary`；
- DIFF evidence 使用 replace 而不是 append；
- `PlanExecuteAgent` 建立 Task baseline；
- 首次执行/evidence retry/reviewer retry 前刷新真实 DIFF；
- 新增 `PlanDiffEvidenceIntegrationTest`；
- 更新 `TaskEvidenceCollectorTest`。

#### 3. `50d1e8b8ede5589082e4742cce429cf48be19310`

```text
docs(plan): document real DIFF evidence
```

内容：

- 更新既有 Plan 多 Agent / evidence 文档，反映真实 DIFF 行为。

#### 4. `ecf66e70226c35c6bbed8be44a30b7247af35f57`

```text
docs(plan): add DIFF evidence validation coverage
```

内容：

- 更新 AGENTS 验证矩阵/开发约束中的 DIFF evidence 覆盖说明。

### 6.3 本次实际代码影响文件

截至本文创建时，本功能分支相对基线涉及：

```text
src/main/java/com/codeagent/agent/TaskWorkspaceDiffTracker.java
src/main/java/com/codeagent/agent/TaskEvidenceCollector.java
src/main/java/com/codeagent/agent/PlanExecuteAgent.java

src/test/java/com/codeagent/agent/TaskWorkspaceDiffTrackerTest.java
src/test/java/com/codeagent/agent/TaskEvidenceCollectorTest.java
src/test/java/com/codeagent/agent/PlanDiffEvidenceIntegrationTest.java

docs/dev/03-multi-agent-collaboration.md
docs/dev/12-plan-evidence-and-conflict-aware-scheduling.md
AGENTS.md
```

本文自身：

```text
docs/dev/30-plan-real-diff-evidence.md
```

### 6.4 流程偏差说明

本次开发在会话中断恢复后出现流程偏差：

- 先产生了核心实现提交；
- 后补了既有 docs/dev 与 AGENTS 同步；
- 用户随后明确要求保留一份本次修改专属、完整、可审计的单独设计文档，因此新增本文。

提交顺序不影响代码语义，但本文必须完整记录该事实，避免把事后文档伪装成实现前设计。

## 7. 当前验证状态

2026-09-28 在本地分支最终源码上完成：

- 扩展针对性测试：65 项，0 failure，0 error，1 skipped；
- 新增边界测试：13 项，0 failure，0 error；覆盖 16 MiB 文本缓存上限和非法 exclude 的执行前硬门禁；
- `mvn test -Pquick -DskipTests=false`：1159 项，0 failure，0 error，4 skipped；
- `mvn test -DskipTests=false`：1217 项，0 failure，0 error，10 skipped；
- `mvn package -DskipTests`：BUILD SUCCESS；
- `git diff --check` 与 `git diff --check main`：通过。

`mvn clean package -DskipTests` 曾在 clean 阶段因 VS Code Java 语言服务占用 `target/classes` 而失败，尚未进入编译；随后在同一最终源码上执行不删除被占用目录的 `mvn package -DskipTests`，编译、测试编译、jar 与 shade 均成功。该环境限制不属于源码或测试失败。

## 8. 已知限制与后续风险

1. **文件类型识别是启发式**
   当前以 NUL 字节判断 binary；不是 MIME/charset 完整识别。

2. **大文本文件不做行级 diff**
   超过阈值只比较 SHA-256，因此能证明内容变化，但 `additions/deletions` 不包含该文件。

3. **workspaceWrite 扫描成本与项目规模相关**
   已排除常见构建目录，但超大源码仓库仍可能有扫描开销。

4. **EvidenceType.DIFF 仍代表“workspace content diff”而非 Git patch**
   它证明观察 scope 内有真实内容变化，不保证变化语义正确；语义正确性仍由 TEST/BUILD/LSP/Reviewer 等其它门禁共同完成。

5. **重试使用 Task 初始 baseline**
   这是有意设计：最终证据回答的是“这个 Task 最终相对开始时改变了什么”，而不是“最近一次 retry 改了什么”。

6. **writePaths 准确性仍依赖 Planner 声明与现有资源治理**
   普通 Task 的 tracker 只观察声明写集；若未来允许某种工具绕过 writePaths 修改其它文件，则 DIFF tracker 不应被用来掩盖该权限缺陷，应由资源策略本身拒绝。

## 9. 审计检查清单

### 设计与行为

- [x] DIFF 不再由 `write_file.successful` 直接生成。
- [x] 真实 before/after 使用 JGit HistogramDiff。
- [x] 普通并行 Task 只观察自己的 writePaths。
- [x] workspaceWrite Task 可观察整个项目且依赖独占调度。
- [x] Side-Git excludes 在全工作区 scope 复用。
- [x] retry 后重新基于初始 baseline 计算。
- [x] 最新 DIFF 替换旧 DIFF，避免 stale PASSED。
- [x] DIFF 失败保持 fail-closed。
- [x] evidence 不保存源码正文。

### 测试

- [x] Tracker 单元测试已编写。
- [x] Collector 单元测试已更新。
- [x] Plan no-op write 集成测试已编写。
- [x] 针对性 Maven 测试实际通过。
- [x] Plan 回归测试实际通过。
- [x] quick 回归实际通过。
- [x] 全量测试实际通过。
- [x] build 实际通过。
- [x] diff check 实际通过。

### 交付

- [x] 单独功能分支。
- [x] 分阶段提交。
- [x] 独立审计文档。
- [x] 最终验证结果回填本文。
- [ ] 最终与 main 合并由用户自行决定。

## 10. 验收标准

只有以下条件全部满足，才能将本功能标记为“完成”：

1. no-op `write_file` 不能满足 required DIFF；
2. 真实新增/删除/修改能稳定生成 DIFF/PASSED；
3. 并行不相交 writePaths 的 Task 证据互不污染；
4. retry 撤销变更后不能残留旧 PASSED；
5. DIFF 采集异常不能 fail-open；
6. 原有 TEST/BUILD/LSP/TOOL_RESULT evidence 行为不回归；
7. Plan 的 Reviewer 与 UNVERIFIED 终态行为不回归；
8. 针对性测试、quick 回归、全量测试和构建结果被真实记录；
9. 文档与最终源码保持一致。

## 11. 2026-10-12 扩展：Reviewer 实际 patch 与命令证据

### 11.1 背景、目标与非目标

分支 `feat/plan-review-diff-and-test-evidence` 从同步后的 main 创建，初次交付保持未提交，后续按用户明确授权分批提交。目标：Reviewer 收到 Task 初始版本到最终版本的 unified patch、验收标准和执行证据；非零退出、超时、启动失败不能形成 PASSED；不同测试命令的失败不能被其他成功命令掩盖。非目标：Reviewer 工具权限、测试报告/断言数量解析、容器隔离、计划结构和自动提交。退出码成功只代表命令成功，不宣称执行了多少测试。

### 11.2 现状分析与源码证据

`TaskWorkspaceDiffTracker.diff` 仅输出 hash 和行统计；baseline 仅持久化 hashes，恢复无旧正文。`PlanExecuteAgent.startDiffTracker` 仅在 required DIFF 时建立基线，`applyStepReview` 只发结果和证据。`SubAgentStepReviewer` 未显式传入验收标准。`ToolRegistry.executeCommand` 返回正常完成文本，即使 exitCode 非零，默认 `classifyTextOutput` 仍标记成功；Collector 直接信任此标记。Gate 只需任意 PASSED，可掩盖 FAILED。

### 11.3 方案设计、边界和恢复

新增 `TaskReviewDiff(patch, complete, issues)`。Tracker 生成实际 unified diff，覆盖新增/删除/修改和上下文，始终相对首次基线，重试不重置。单文件文本仍限 2 MiB、基线缓存仍限 16 MiB，patch 输出最多 48 KiB；二进制、缺失旧正文或截断明确标为不完整，不允许 Reviewer 批准，Task 为 UNVERIFIED，不解锁后继。不完整 diff 不通过无意义 Worker 重试扩大权限。写任务启用 stepReview 时即使未声明 DIFF 也建立基线；只读任务无 patch 要求。

复用 `SideGitManager` 对象库，通过内存 DirCache + ObjectInserter 保存 Task 写集中的已缓存正文，创建独立无父 commit 和 `refs/codeagent/task-baselines/` 引用；不改 HEAD/index/worktree，不扫描其他 Task 文件。SQLite baseline 增加可选 snapshotCommitId，仍无源码；旧 hash-only JSON 兼容，恢复不建立新基线。读回正文限量并核验原 SHA-256；引用丢失/快照关闭时保留 hash 证据但 review diff 不完整。启用快照时保存失败必须在 Worker 前失败关闭。引用随显式快照清理删除，暂不自动按 Plan 回收，避免损坏恢复中的 Plan；长期存储成本是已知限制。

路径必须留在工作区和声明写集，拒绝符号链接祖先逃逸，恢复路径重新验证。Side-Git 接口 synchronized，与已有 writer 串行，不获得 SQLite 锁；顺序为写 snapshot、持久化 baseline、运行 Worker，持久化失败不运行 Worker。并行 Task 使用独立 tracker/ref/reviewer。Reviewer 保持无工具，patch 和 Worker 摘要是不可信执行材料，不能产生权限。证据门禁失败不得靠 Reviewer 批准绕过。patch 可能进入既有本地 session ledger，沿用敏感正文权限约束，禁止放入 SQLite/audit 摘要或交付报告。

execute_command 返回 typed ToolOutput，真实 exitCode 决定成功，超时/异常/取消分类失败，展示文本兼容。Collector 保留相同完整命令的最新 TEST/BUILD 证据，不能用截断摘要作身份；不同命令分别保留。Gate 对 TEST/BUILD 要求至少成功一次且无未解决失败。明显 skip-tests 命令不能成为 TEST/PASSED；命令分类采用明确程序入口而非 echo 文本。保留有界输出摘要供 Reviewer 判断，输出文本不能作为成功或权限来源。

审批改参后的实际命令由 native ToolOutput 的 structuredContent.executedCommand 传播到 ToolExecutionResult.executedCommand；调用 ID、原 argumentsJson 与 ledger 身份不改写。Collector 以实际命令分类和去重；旧 9 参数 ToolExecutionResult 构造兼容，可信旧调用者仍按原 invocation 描述执行命令。分类器只接受无 shell 控制/替换操作的字面参数，按 Maven/Gradle 目标、包管理器子命令和 pytest/cargo/go 入口识别；选项值不能冒充 test 目标。version/help/dry-run/collect-only/no-run/ignore-scripts、Maven fail-never、Gradle 排除 Task 等明显不执行或掩盖失败的模式不产生 PASSED，未知组合保守降为 TOOL_RESULT。stdout 不用来猜测批准或生成权限；任意自定义脚本是否真正执行断言仍需 Reviewer 检查，不解析测试数量。

Tracker 使用所属 SnapshotService.manager 的实际 excludes（包括可信宿主自定义配置），在 workspaceWrite 的初始捕获、当前捕获和恢复验证中保持一致。transient Plan 只保留内存 baseline，仍用该 manager 的排除规则；仅 durable Plan 保存 Task 正文引用。显式声明的 writePaths 保持既有语义，不额外禁用已授权的排除路径。

兼容/回滚：新代码可读旧 hash-only baseline，不自动重置或改写它。旧程序可能拒绝新 snapshotCommitId 字段且失去新增审核保证；回退程序前应停止执行、处理或显式放弃新建活动 Plan，再回退代码。不得自动删除计划或源码。Side-Git task refs 可保留；显式清理后旧正文不可恢复，当前版本会失败关闭。

```mermaid
sequenceDiagram
    participant P as Plan
    participant S as SideGit
    participant DB as SQLite
    participant W as Task Worker
    participant D as Tracker/Gate
    participant R as Reviewer
    P->>D: 捕获 Task 初始写集
    D->>S: 有界正文与独立引用
    P->>DB: hashes + snapshotCommitId
    P->>W: 执行和重试
    W-->>D: typed 工具证据
    D->>D: 初始 baseline 到最终 diff 和 gate
    alt 完整且证据满足
        P->>R: 验收标准 + patch + 执行证据
        R-->>P: 结构化批准或拒绝
    else 缺失、截断或失败
        D-->>P: 未验证，不解锁后继
    end
```

### 11.4 实现顺序与测试矩阵

1. ToolRegistryTest 先复现 exit 7 被误判成功；实现 typed 命令结果，回归 timeout/取消/策略。Collector/Gate 测试覆盖失败重跑、不同命令失败、skip/echo 和实际输出摘要。
2. Tracker 测试覆盖实际 patch/新增删除/范围隔离/预算不完整/legacy 恢复；SideGitManagerTest 覆盖 scoped 保存、不改 HEAD/index、跨实例读回和关闭。
3. Reviewer 测试捕获 LLM 输入，验收标准/patch/证据完整且无工具；不完整 diff 不发批准请求。Plan 集成测试覆盖未要求 DIFF 的写任务、重试初始 baseline 和恢复正文。
4. 同步 AGENTS、README 和 implementation 文档；运行针对性、quick、全量、构建和 git diff --check，真实结果回填下节。

### 11.5 验收清单与实施记录

- [x] Reviewer 实际输入包含 unified patch、验收标准和真实执行证据。
- [x] SQLite 无源码，Task 快照不改 HEAD/index，恢复使用原 baseline。
- [x] 不完整 patch、失败证据不能完成 Task 或解锁后继。
- [x] 原有并发范围、HITL 和无工具 Reviewer 契约保持有效。
- [x] 针对性/quick/全量/构建/diff 检查结果真实记录。

本次基线为 `main@cb7cf06c75893ac931b21c4a6be8c487deb7611b`，已确认与 origin/main 一致后创建分支。初次验证交付时改动保持未提交。

测试先行证据：`ToolRegistryTest#nativeCommandNonZeroExitIsTypedFailure` 在原实现失败，exit 7 被报告 successful=true；typed 修复后通过。Plan patch 输入测试在原接线上观察到 0 个实际 patch（预期 2）；补齐接线后通过。写任务缺失 patch 的 Reviewer 测试先返回 APPROVED（预期 UNAVAILABLE），增加材料门禁后通过。新增 API 测试最初因接口尚未实现编译失败；测试夹具的 IOException 和 Message 限定名错误已修正，随后执行 Maven javac 全量重编译和针对性测试通过。

已执行针对性命令（Java 17）：

```powershell
mvn test -DskipTests=false "-Dtest=TaskReviewDiffTest,TaskWorkspaceDiffTrackerTest,PlanDiffEvidenceIntegrationTest,PlanExecuteRecoveryTest,SubAgentStepReviewerTest,TaskEvidenceCollectorTest,DeterministicEvidenceGateTest,ToolRegistryTest,SideGitManagerTest,PlanExecuteAgentTest,MainPlanAgentFactoryTest,PlanStateStoreTest,PipelineOptionsTest"
```

结果：138 项，0 failure，0 error，2 项既有 skip。另已验证实际离线 Maven 无 POM 失败，通过 native tool → collector → gate 的完整链路保持 FAILED/REJECTED。

环境记录：`mvn clean test` 在 clean 阶段因 IDE 占用 `target/classes` 目录失败。该命令未运行测试；清理已移除 Maven 编译状态，随后 `mvn test` 使用 javac 重新编译全部 377 个主源码及 266 个测试源码，100 项针对性测试通过。没有停止用户 IDE，也未把 clean 失败写成验证成功。

独立只读审查发现并复现三项 Important：HITL 最终参数未用于证据、custom snapshot excludes 未传入 Tracker、非执行命令被误归 TEST。三个新用例在修复前全部失败；修复后的 collector/tracker/HITL/ToolRegistry 回归 89 项全部通过。已补实际命令元数据、跨保存/恢复的真实 excludes 和字面子命令分类，并加入 verbose 测试正例避免过度拒绝。审查修复前 quick 1451 项/全量 1526 项均 0 failure、0 error，但不能作为最终源码验收；后续最终复跑结果单独记录。

独立复审确认三项已闭合，未发现阻断交付的新问题。最终 quick 首次复跑发现既有非法 excludes 测试在 manager 创建之后才改全局配置，已调整为构造前注入，不改变生产实现或失败关闭断言。修正后七类针对性测试 98 项、0 failure、0 error、1 项既有 skip；最终 quick 使用 Maven javac 重编译全部 377 个主源码、266 个测试源码，`mvn test -Pquick -DskipTests=false` 结果 1455 项、0 failure、0 error、18 项既有 skip。

最终 `mvn test -DskipTests=false` 全量结果：1530 项、0 failure、0 error、24 项既有 skip。跳过包括当前平台不支持的 stdio transport 用例及既有显式禁用/环境依赖用例，不宣称全部执行。

最终 `mvn package -DskipTests` 构建成功，生成 shaded JAR；保留既有依赖重叠/module-info 的 shade 警告。`git diff --check` 通过，新增文件另行检查行尾空白通过。`clean` 的目录占用限制仍存在，不能宣称 clean 构建通过。初次交付未执行 commit/push/PR/merge。

用户随后明确要求“分批提交这些修改”，按依赖顺序提交：`6bfaf8b` 修复实际命令执行与 TEST/BUILD 门禁；`e953ed5` 增加 Reviewer 实际 patch 与持久化基线；第三批同步指南、架构说明及本文验证记录。提交前逐批执行 `git diff --cached --check`；代码与上述通过验证的版本一致，仅更新交付状态记录。不执行 push/PR/merge。
