# 统一 Session Execution Queue 与 ReAct / Plan 调度

## 1. 背景、目标与非目标

### 1.1 背景

当前项目的顶层执行存在明显分裂：

1. **普通交互式消息**
   - CLI 读取一条用户输入；
   - 当前 Turn 进入 ExecutionModeRouter；
   - Router 选择 ReAct 或 Plan；
   - 当前 Turn 执行完成后，CLI 才重新读取下一条输入；
   - 普通 Turn 不进入 durable queue。

2. **/task add**
   - 命令直接写入 runtime_tasks；
   - 固定 Worker Pool 从 SQLite 领取；
   - 当前执行体固定创建普通 Agent，绕过 Auto Router；
   - 与普通 ReAct / Plan 顶层 Turn 是另一套生命周期。

这导致两个问题：

- 同样是一条顶层用户任务，“普通输入”和“/task add”拥有不同的持久化、排队和恢复能力；
- Agent 正在运行时，用户不能继续发送下一条普通消息并让它排队，只能等待当前 Turn 返回或改用特殊命令。

本次重构不再把“后台任务”视为特殊执行路径，而是统一抽象：

> **每一条顶层用户任务消息都是一个 durable Execution。**

ReAct / Plan 只决定 Execution 如何执行，不决定它是否进入队列。

层级固定为：

~~~text
Session
  │
  ├─ Execution 1
  │    └─ ReAct
  │
  ├─ Execution 2
  │    └─ Plan Run
  │         ├─ Plan Task A
  │         ├─ Plan Task B
  │         └─ Plan Task C
  │
  └─ Execution 3
       └─ ReAct / Plan
~~~

### 1.2 核心目标

本次重构必须达到：

1. inline/plain CLI 的所有普通顶层任务消息都先持久化为 Execution，再由 Scheduler 执行；不再存在“普通消息直跑、只有 /task 才入队”的双轨路径。
2. 同一个 workspace 内的顶层 Execution 严格串行：
   - Execution N 未终态时，Execution N+1 只能 ENQUEUED；
   - N+1 真正开始时读取 N 已提交的最新 Session 上下文。
3. 第一阶段每个 CLI/runtime 实例只绑定当前 workspace，并只有一个顶层 Execution worker；不同 Session 也共享这个 workspace slot。并行只保留在 Plan 内部最多 4 个无冲突 Task，不宣称跨 workspace/多进程调度。
4. 每个 Execution 启动后统一进入：
   - Auto Router -> ReAct / Plan；
   - /react / /plan 只作为该 Execution 的 one-turn override。
5. 用户在 Agent 正在运行时仍可输入下一条普通消息；CLI 立即持久化并显示 queue position，不等待当前 Execution 完成。
6. ReAct 与 Plan 都拥有统一的 durable 顶层 Execution identity：
   - ReAct crash：第一阶段从 Execution / Turn 边界恢复，保持 at-least-once；
   - Plan crash：复用现有 DAG node recovery。
7. Runtime Execution 与 Plan Task 保持不同实体：
   - Runtime Execution = 一条顶层用户 Turn；
   - Plan Task = 某个 Plan Execution 内部的 DAG node。
8. Session Event Log 继续是对话上下文事实源；Execution Store 负责排队、模式、顶层状态和恢复身份，不复制整段 Provider Surface。
9. ReAct / Plan 对 Runtime 返回 typed outcome，不能再根据字符串内容猜 COMPLETED / FAILED / CANCELED。
10. Cancellation 必须 execution-scoped，并传播到 Router、ReAct、Plan 和 Plan child workers。
11. 当前 Execution 的权限只来自自己的 raw submitted input；历史对话不能产生新的路径、URL 或 HITL 权限。
12. 保留 /task list、/task cancel、/task log 作为 Execution 管理命令；/task add 只作为同一 enqueue API 的兼容入口。

### 1.3 非目标

本次不做：

- 不实现“新消息立即注入当前正在运行 LLM Turn”的 mid-turn steering；普通新消息只进入下一 Execution。
- 不把 Plan Task 放入 Runtime Execution Queue。
- 不允许同一个 workspace 同时运行两个顶层 Execution；Session FIFO 仍用于保持会话因果顺序。
- 不改变 Plan 内部最多 4 路无冲突 Task 并行、ResourceClaims、Evidence Gate、Reviewer。
- 不实现多进程 lease、heartbeat、priority、dead-letter、exactly-once。
- 不让一个 CLI/runtime 实例领取其它 workspace 的 Execution；跨 workspace 并行留待具备 lease/owner 隔离后单独设计。
- 不一次性迁移 Runtime HTTP API、Lanterna TUI；第一阶段只改目前有 Auto Router 的 inline/plain CLI。
- 不取消现有 Plan 人工审阅或 HITL；执行线程化后通过 InteractionBroker 接回 CLI。
- 不把 queued message 提前写入 ParentConversationContext。
- 不再为 /task add 创建独立 Background Session 或 fork。

---

## 2. 现状分析

### 2.1 普通 Turn 当前是同步阻塞路径

当前 Main 的产品级时序是：

~~~text
readLine
  ↓
解析命令 / 输入展开
  ↓
ExecutionModeRouter
  ↓
runWithCancelSupport
  ↓
ReAct / Plan 完整执行
  ↓
返回结果
  ↓
下一次 readLine
~~~

所以“有 SQLite 队列”并不等于已经拥有可排队的聊天体验。

要支持运行时继续输入，必须把输入采集和 Execution 执行解耦：

~~~text
CLI Input Loop
  └─ command / enqueue / interaction response

Execution Worker
  └─ Router + ReAct / Plan

UI Bridge
  └─ 串行化 streaming / status / queue output
~~~

### 2.2 /task add 当前是 ReAct-only 旁路

/task add 在 CLI command switch 中处理后直接 continue，因此不会进入普通输入后面的 Auto Router。

现有 DurableTaskManager 已有：

- SQLite durable queue；
- 固定 Worker Pool；
- ENQUEUED -> RUNNING -> terminal；
- CAS 式领取；
- running cancel；
- stale RUNNING startup recovery。

这些能力可以作为新 Scheduler 的实现基础，但不能继续作为“另一套 Agent 模式”。

### 2.3 Runtime Execution 与 Plan Task 不能合表

Runtime Execution 回答：

> “用户这一轮顶层任务现在排队、运行、成功、失败还是取消？”

Plan Task 回答：

> “如果这一轮选择 Plan，内部哪个 DAG node 可执行、依赖和 Evidence 是否满足、恢复到哪里？”

~~~mermaid
graph TB
    S[Session] --> E1[Execution 1]
    S --> E2[Execution 2]
    S --> E3[Execution 3]

    E1 --> R1[ReAct]
    E2 --> P1[Plan Run]
    E3 --> R2[ReAct or Plan]

    P1 --> T1[Plan Task 1]
    P1 --> T2[Plan Task 2]
    P1 --> T3[Plan Task 3]
~~~

所以统一的是“顶层 Turn 调度”，不是把所有带 status 的实体塞进同一张表。

### 2.4 Session 已经是上下文事实源，但缺统一 Execution envelope

现有 ReAct / Plan 已共享 ParentConversationContext：

- ReAct 使用 Provider Surface；
- Router / Planner 使用 Top-level Conversation View；
- raw Session Event Log append-only；
- Session resume 可识别 incomplete request / pending tool。

但普通 ReAct Turn 没有 durable Execution row，因此 crash 后虽然能恢复上下文，却不能稳定表达：

~~~text
execution_x
status = RUNNING
mode = REACT
submitted_input = ...
~~~

Plan 有 plan_runs / plan_tasks，所以节点级恢复更强；本次需要给两种模式补上统一的顶层身份。

### 2.5 queued message 不能在 enqueue 时进入对话上下文

例如 Execution 1 正在运行，用户输入：

~~~text
“改完以后再跑一下全部测试”
~~~

它应该创建 Execution 2 并排队。

如果 enqueue 时就 append 到 ParentConversationContext，Execution 1 的后续 LLM iteration 可能看到这条“未来消息”，破坏 Turn 边界。

因此：

- enqueue：只写 Execution Store；
- Execution 2 真正开始：才把该 User message append 到 Session；
- 此时 Execution 1 已终态，所以 Execution 2 自然看到 Execution 1 的最终结果。

### 2.6 Plan 需要绑定 execution_id

当前 PlanStateStore 主要用 workspace + session_id 管 active Plan。

统一 Execution Queue 后，一个 Session 会顺序产生多个 Plan Execution，因此新 Plan 必须增加 execution_id 绑定，恢复时才能确定“哪个 Plan lineage 属于当前 Runtime Execution”。

### 2.7 String 返回值不能作为 Runtime 终态

Agent.run 与 PlanExecuteAgent.run 都可能捕获内部错误并返回错误字符串。

所以新 Runtime 不能继续：

~~~java
String result = run(...);
markCompleted(result);
~~~

必须提供 typed outcome。

### 2.8 当前 CancellationContext 不适合跨 Session 并发

统一 Queue 后顶层 Execution 在当前 workspace 串行，但 Plan 内和 ToolRegistry 内仍会创建 child worker，因此 token 仍必须显式跨 executor 传播。

全局 CURRENT + InheritableThreadLocal 不能作为可靠并发传播模型，必须改成显式 execution scope。

---

## 3. 方案设计

### 3.1 总体架构

~~~mermaid
flowchart TB
    I[CLI Input Loop] --> D{Control or Task Input}

    D -->|control command| CTRL[Control Plane]
    D -->|ordinary task| SUB[RuntimeExecutionQueue.submit]
    D -->|/react payload| SUB
    D -->|/plan payload| SUB
    D -->|/task add payload| SUB

    SUB --> DB[(runtime_executions)]
    DB --> SCH[WorkspaceAwareExecutionScheduler]

    SCH --> W[Single top-level Execution Worker]

    W --> C[TopLevelExecutionCoordinator]

    C --> R[ExecutionModeRouter]
    R -->|REACT| A[Agent]
    R -->|PLAN| P[PlanExecuteAgent]

    A --> S[(Session Event Log)]
    P --> S
    P --> PS[(plan_runs / plan_tasks)]

    A -.HITL.-> IB[InteractionBroker]
    P -.Plan Review / HITL.-> IB
    IB --> I
~~~

三条铁律：

1. 所有普通顶层任务先 enqueue，再执行。
2. 当前 workspace 顶层严格串行；同 Session 额外保持 FIFO，Plan 内部并行不变。
3. ReAct / Plan 是 Execution 的 mode，不是 submission type。

### 3.2 顶层模型 RuntimeExecution

建议模型：

~~~java
record RuntimeExecution(
    String id,
    String workspace,
    String sessionId,
    Long ordinal,
    ExecutionStatus status,
    String submittedInput,
    String resolvedTaskInput,
    ExecutionMode explicitMode,
    ExecutionMode selectedMode,
    RoutingSource routingSource,
    ExecutionOutcome outcome,
    String result,
    String error,
    int attempt,
    boolean legacyUnbound,
    Instant cancelRequestedAt,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    Instant updatedAt
) {}
~~~

第一阶段状态：

~~~text
ENQUEUED
RUNNING
COMPLETED
FAILED
CANCELED
~~~

WAITING_REVIEW / WAITING_HITL 暂不做 durable 状态，仍属于 RUNNING 的内部 phase。

### 3.3 canonical 表 runtime_executions

目标 schema：

~~~sql
CREATE TABLE runtime_executions (
    id                  TEXT PRIMARY KEY,
    workspace           TEXT NOT NULL,
    session_id          TEXT,
    ordinal             INTEGER,
    status              TEXT NOT NULL CHECK (
                            status IN ('enqueued','running','completed','failed','canceled')
                        ),
    submitted_input     TEXT NOT NULL,
    resolved_task_input TEXT,
    explicit_mode       TEXT,
    selected_mode       TEXT,
    routing_source      TEXT,
    outcome             TEXT,
    result              TEXT,
    error               TEXT,
    attempt             INTEGER NOT NULL DEFAULT 0,
    legacy_unbound      INTEGER NOT NULL DEFAULT 0 CHECK (legacy_unbound IN (0,1)),
    cancel_requested_at TEXT,
    interruption_reason TEXT,
    created_at          TEXT NOT NULL,
    started_at          TEXT,
    finished_at         TEXT,
    updated_at          TEXT NOT NULL,
    UNIQUE(session_id, ordinal),
    CHECK (
        (legacy_unbound = 1 AND session_id IS NULL AND ordinal IS NULL)
        OR
        (legacy_unbound = 0 AND session_id IS NOT NULL AND ordinal IS NOT NULL)
    )
);
~~~

索引：

~~~sql
CREATE INDEX idx_runtime_executions_queue
ON runtime_executions(workspace, status, created_at);

CREATE UNIQUE INDEX idx_runtime_executions_one_running_per_session
ON runtime_executions(session_id)
WHERE status = 'running' AND legacy_unbound = 0;

CREATE UNIQUE INDEX idx_runtime_executions_one_running_per_workspace
ON runtime_executions(workspace)
WHERE status = 'running' AND legacy_unbound = 0;
~~~

持久化状态统一使用小写，与现有 `runtime_tasks` 兼容；Java 枚举可以保持大写名称，但数据库读写必须经过显式 codec，未知值 fail closed，禁止继续沿用“未知状态回退 ENQUEUED”。

`session_id/ordinal` 只为 legacy-unbound 兼容行允许 NULL。所有新 enqueue 都必须由 Store 校验为非空；Scheduler 永远排除 `legacy_unbound = 1`。

### 3.4 所有普通输入统一 enqueue

普通输入：

~~~text
> 帮我重构支付模块
~~~

执行：

~~~text
RuntimeExecutionQueue.submit(
  currentSessionId,
  submittedInput,
  explicitMode = null
)
~~~

`workspace` 在构造 `RuntimeExecutionQueue` 时绑定，不是 `submit` 的参数。

/react payload：

~~~text
explicitMode = REACT
~~~

/plan payload：

~~~text
explicitMode = PLAN
~~~

/task add payload：

~~~text
与普通消息完全相同的 enqueue
~~~

它仅保留兼容性，不再代表独立后台任务模型。

### 3.5 当前 workspace 串行与 Session FIFO

规则：

1. enqueue 在 SQLite 写事务中为该 Session 分配单调递增 ordinal；`MAX(ordinal)+1` 的读取与 INSERT 必须位于同一 `BEGIN IMMEDIATE` 事务，唯一约束冲突只能有限重试；
2. 每个 workspace 最多一个 RUNNING；每个 Session 同样最多一个 RUNNING；
3. 只有该 Session 最小 ordinal 的 ENQUEUED Execution 可被领取；
4. Scheduler 构造时绑定规范化 workspace，只能 claim 该 workspace，顶层 executor 固定为单 Worker；
5. Plan Execution 内部仍可最多 4 个无冲突 Task 并行。

概念 claim：

~~~sql
SELECT e.*
FROM runtime_executions e
WHERE e.workspace = ?
  AND e.status = 'enqueued'
  AND e.legacy_unbound = 0
  AND NOT EXISTS (
      SELECT 1 FROM runtime_executions r
      WHERE r.workspace = e.workspace
        AND r.status = 'running'
  )
  AND NOT EXISTS (
      SELECT 1 FROM runtime_executions p
      WHERE p.session_id = e.session_id
        AND p.status = 'enqueued'
        AND p.ordinal < e.ordinal
  )
ORDER BY e.created_at ASC
LIMIT 1;
~~~

然后 CAS：

~~~sql
UPDATE runtime_executions
SET status='running', ...
WHERE id=? AND status='enqueued' AND legacy_unbound=0;
~~~

示例：

~~~text
Current workspace / Session A: A1 RUNNING -> A2 ENQUEUED -> A3 ENQUEUED
Current workspace / Session B: B1 ENQUEUED
~~~

B1 即使属于另一 Session，也必须等待 A1。A2 还必须遵守 Session A 的 ordinal 顺序。顶层不并行，A1 若选择 Plan，Plan 内部仍可按 ResourceClaims 最多 4 路并行。

### 3.6 SessionExecutionContextRegistry 与 Session 生命周期

`Main` 不再让单个长期存在的 `Agent` 在 Session 之间反复换绑。`SessionExecutionContextRegistry` 是进程内 Session 运行态的唯一所有者：

~~~text
SessionExecutionContextRegistry
└─ sessionId -> SessionExecutionContext
                  ├─ one writable SessionHandle
                  ├─ one ParentConversationContext
                  ├─ one Agent
                  ├─ one MemoryManager
                  ├─ one ToolRegistry / SnapshotService binding
                  └─ provider/model binding
~~~

规则：

1. CLI 的 `activeSession` / `activeAgent` 只是当前 UI 选择指针，不负责关闭对象；writable handle 与 Agent 的所有权只在 Registry；
2. Worker claim 后按 `sessionId` 向 Registry 取得 execution lease；当前 inline/plain 进程的 ToolRegistry/MCP 注册表是共享基础设施，因此 Registry 采用进程级单 writable lease，既禁止同 Session 重入，也禁止不同 Session 同时改写共享绑定；
3. Registry 首次访问时用 `SessionStore.resumeWritable(sessionId, workspace)` 懒加载，已加载则复用同一对象，禁止第二个 writable handle；
4. Execution 完成只释放 lease，不立即关闭仍可能被后续 queued Execution 使用的 Session；
5. 只有 Session 没有 RUNNING/ENQUEUED Execution、没有 pending interaction 且不再是 CLI 当前 Session 时，Registry 才可驱逐并关闭 handle；
6. CLI EOF/正常退出先触发 Scheduler 的 `RUNTIME_SHUTDOWN`，存在非终态 Execution 的 Session 保持 unclosed，不写 `SESSION_END`；下次启动由 Session + Execution recovery 继续；
7. `/new`、`/resume` 只切换 CLI 当前 sessionId，但源 Session 或目标 Session 存在非终态 Execution/pending interaction 时拒绝切换；状态查看继续通过只读命令完成；
8. 第一阶段 provider/model 使用 Session 当前绑定；会改变 provider/model 的命令仅允许在该 Session 无非终态 Execution 时执行，避免 queued Execution 在等待期间被静默换模型。

`SessionExecutionContext` 独占 Agent、ParentConversationContext、MemoryManager、SkillContextBuffer 和 writable SessionHandle；进程级动态 MCP/Browser/HITL 工具注册仍由共享 ToolRegistry 承载。取得 lease 时通过 `Agent.activateSharedToolContext()` 原子地重绑当前 Session 的 memory writer、context profile、provider/model 与 skill buffer，执行其他 Session 的恢复任务后再恢复 CLI 当前 Session 的绑定。Scheduler 执行和 CLI 的 Session/Runtime mutation 还共享一把进程级互斥锁：Worker 阻塞取得，CLI mutation 使用 `tryLock` fail closed，避免恢复其他 Session 时 `/model`、`/config`、`/new` 等命令并发改写共享绑定。若未来放开跨 workspace 顶层并行，必须先把动态 MCP 注册复制或拆分到各 Context，不能移除当前全局 lease 后直接并行。

Registry 依赖 SessionStore、Execution Store 和执行上下文工厂；不得反向依赖 JLine 或具体 Renderer。

### 3.7 上下文语义：start-time continuation

这是最终方案与前一版 fork-at-submit 的根本区别。

E1 运行时提交 E2：

~~~text
Session S
  E1 RUNNING
  E2 ENQUEUED
~~~

E2 enqueue 时：

- 不写 ParentConversationContext；
- 只持久化 raw submitted input。

E1 完成后：

~~~text
Session context
  ...历史
  E1 user
  E1 assistant / Plan final
~~~

E2 真正开始时：

1. 读取此时最新 Session Projection；
2. Router 使用最新 Top-level Conversation；
3. ReAct / Planner 使用相同 Session 的当前上下文；
4. 由所选 mode adapter 按 executionId 幂等确保 E2 顶层 User；
5. 执行。

因此 E2 能看到 E1 的最终结果，而不是提交瞬间的中间状态。

### 3.8 输入解析的 durable 边界

普通 CLI 当前有 mention/path/resource expansion。

为了保证 E2 在 E1 完成后再解析，并保证 crash retry 输入一致：

- enqueue 只存 submitted_input；
- Execution 首次 RUNNING 时调用统一 ExecutionInputResolver；
- resolver 成功后 durable 写 resolved_task_input；
- Router 始终使用 raw `submitted_input`；Agent / Planner 的任务交付使用 `resolved_task_input`；
- retry 若字段已存在则直接复用，不重新解析；
- TurnToolPolicy 始终只看 submitted_input。

这要求把 Main 中与 UI 无关的输入展开抽成可复用组件。

### 3.9 Session Execution Envelope

新增 required Session event（命名遵循现有 slash 风格）：

~~~text
execution/start
  executionId
  ordinal
  explicitMode

execution/end
  executionId
  selectedMode
  outcome
  status
~~~

顶层 USER / ASSISTANT conversation metadata 增加 executionId。`SessionProjection` 增加按 executionId 索引的 immutable envelope view，至少能查询 start、top-level User、top-level Assistant 和 terminal END；`SessionReplayer` 必须把这两个新事件加入 required known types，损坏、重复或次序矛盾时 fail closed。

规则：

- claim 后由 Coordinator 幂等确保 `execution/start`；
- Coordinator 本身不写顶层 User；ReAct/Plan mode adapter 是各自顶层 User 的唯一写入者，并通过 executionId 做幂等；
- ReAct 首次入口 `runExecution(request)` 在 provider call 前确保 User；恢复入口 `resumeExecution(request)` 复用已有 User 和已协调的 incomplete request/pending tool，不再追加 User；
- Plan 继续由 `PlanConversationReconciler` 在 durable Plan turn 中写 semantic User，但必须携带 executionId；Planner 构造 prior conversation 时排除当前 executionId，保证 current goal 只出现一次；
- crash retry 发现同 executionId 已有 User 时必须走 resume 入口，禁止通过文本相等判断；
- typed terminal outcome 由统一 `ExecutionFinalizer` 先写 `execution/end`，再 CAS 更新 SQLite terminal；
- Session 已有 END、SQLite 仍 RUNNING 时，恢复阶段从 Session 收敛 SQLite，不重新执行。

Plan 自己的 TURN_START / TURN_END 可以保留，但必须能关联 executionId。

### 3.10 TopLevelExecutionCoordinator

职责：

1. 取得 RuntimeExecution；
2. 确保 resolved_task_input 已 durable；
3. 取得当前 Session Top-level Conversation；
4. explicit override 或 Auto Router；
5. 通过 Execution Store 的 `commitRoutingDecision(executionId, mode, source)` 完成 selected_mode / routing_source durable ack；字段为空时写入，已存在相同值时幂等返回，已存在不同值时 fail closed；
6. 只有 ack 成功后才允许开始 ReAct / Plan 副作用；
7. SnapshotService.runTurn；
8. 返回 typed outcome。

建议：

~~~java
enum ExecutionOutcome {
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELED,
    REJECTED
}

record TopLevelExecutionResult(
    ExecutionMode mode,
    RoutingSource routingSource,
    ExecutionOutcome outcome,
    String result,
    String error
) {}
~~~

状态映射：

~~~text
SUCCEEDED -> COMPLETED
PARTIAL   -> COMPLETED，result 明确标记为部分交付
FAILED    -> FAILED
CANCELED  -> CANCELED
REJECTED  -> FAILED + reason
~~~

禁止根据“❌”等展示文本判断状态。

完整退出映射：

| 退出路径 | ExecutionOutcome | Runtime status | 说明 |
|---|---|---|---|
| ReAct/Plan 正常最终回答 | SUCCEEDED | completed | result 保存可交付结果 |
| ReAct 预算安全阀给出部分结果 | PARTIAL | completed | 不伪装成完整完成，日志/UI 显示 partial |
| resolver、Snapshot、Session durable 写入失败 | FAILED | failed | durable mode ack 失败时不得启动 Agent |
| LLM/工具执行不可恢复失败 | FAILED | failed | error 保存稳定错误摘要，不靠展示字符串 |
| Plan Evidence/Reviewer 重试耗尽或 Plan FAILED | FAILED | failed | 保留 Plan terminal 证据 |
| Plan 因已有不一致 active lineage 被拒绝 | REJECTED | failed | fail closed，不新建 Plan |
| 用户取消 | CANCELED | canceled | 由 cancel_requested_at 裁决 |
| runtime shutdown | 无 terminal outcome | enqueued/running 待恢复 | 不写 execution/end，不映射成用户取消 |

### 3.11 Plan 与 Runtime Execution 绑定

plan_runs 增加：

~~~text
execution_id TEXT
~~~

该列 additive nullable：新 Plan 必须非空，旧 Plan 允许 NULL。增加普通索引 `(workspace, session_id, execution_id, updated_at)`；不能设为 UNIQUE，因为 execution replan 的多个 plan_id 必须属于同一个 execution_id。

新 Plan 必须绑定：

~~~text
workspace
session_id
execution_id
~~~

如果发生 replan，新 plan_id 继续属于同一个 execution_id。

legacy active Plan（`execution_id IS NULL`）不能被 Scheduler 猜测绑定，也不能与新 Runtime Execution 并行。启动时检测到后：

1. 阻止该 workspace claim/提交新的顶层 Execution，并提示 `/plan resume` 或 `/plan abandon`；
2. `/plan abandon` 保持现有语义；
3. `/plan resume` 以确定性 `executionId = legacy-plan-<planId>` 创建一条 PLAN RuntimeExecution，`submitted_input` 取已持久化 `policy_input`，再把 plan_runs.execution_id 绑定为该 ID；
4. tasks.db insert 与 plans.db bind 之间的 crash 通过确定性 ID 收敛：只有 Execution row 时补 bind，只有 bind 时按同 ID 补 row；字段冲突 fail closed；
5. adoption 完成后由正常 Scheduler resume，`/plan resume` 本身不直接执行 Agent/工具副作用。

恢复：

~~~text
selected_mode = PLAN
        │
        ▼
find plan lineage by execution_id
        │
        ├─ active
        │    -> resume，不 reroute / 不重新执行 completed nodes
        │
        ├─ terminal
        │    -> 用 Plan terminal outcome 收敛 Runtime Execution
        │
        └─ no plan
             -> crash 位于 mode durable 后、首次 savePlan 前
             -> 允许第一次 Planner
~~~

### 3.12 ReAct crash recovery

第一阶段不实现工具副作用 exactly-once。

恢复规则：

1. startup 先完成 schema migration，再枚举所有非终态 Runtime Execution；
2. 对每个 executionId 先打开其绑定 Session，并运行 SessionStore incomplete request / pending tool reconciliation、PlanConversationReconciler 和 execution/end -> SQLite reconciliation；
3. 上述恢复全部完成后才启动 Scheduler、开放 CLI 输入和接受 cancel/control command；任一绑定 Session 无法安全打开时，该 workspace 的 Scheduler fail closed；
4. startup 发现没有 terminal END 的 stale RUNNING；
5. 若 Session 已存在同 executionId 的 `execution/end`：
   - 直接收敛 SQLite terminal；
6. 否则把该 Execution 恢复为 ENQUEUED，ordinal 不变；
7. 下一次 claim 不重复 append 顶层 User；
8. ReAct 在同 Execution identity 下继续一次恢复执行；
9. 后续 queued Execution 不能越过它。

这仍是 at-least-once：已经完成的外部 side effect 可能重复，恢复提示必须要求模型检查 workspace / external state。

### 3.13 Plan crash recovery

示例：

~~~text
E1 RUNNING
mode=PLAN
P1 active

task1 COMPLETED
task2 RUNNING
task3 PENDING

E2 ENQUEUED
~~~

重启：

~~~text
E1 -> ENQUEUED
E2 保持 ENQUEUED
  ↓
E1 claim
  ↓
execution_id -> P1
  ↓
task2 -> INTERRUPTED
task1 不重跑
继续 DAG
  ↓
E1 terminal
  ↓
E2 才能开始
~~~

现有 DIFF baseline、Evidence、ResourceClaims、Reviewer、child Session 规则保持不变。

### 3.14 CLI 输入与执行解耦

目标：

~~~text
Main CLI Thread
  ├─ 持续 readLine
  ├─ 处理 control command
  ├─ enqueue ordinary task
  └─ 处理 InteractionBroker response

Execution Worker
  └─ Router / ReAct / Plan

Renderer（唯一终端输出 surface）
  ├─ Agent / Plan streaming -> Renderer.stream()
  └─ CliUiEventBridge -> lifecycle / interaction / queue event
       （InlineRenderer 在读取态统一用 printAbove 保护输入行）
~~~

要求：

- CLI Input Loop 是 Terminal/LineReader 的唯一输入所有者；Worker、PlanReviewHandler、HITL 和取消监听都不得直接读 terminal；
- 现有 `runWithCancelSupport` 的 Worker raw-mode/ESC 读取必须移除；ESC 改为 LineReader widget，在 CLI 输入线程中把取消投递给当前 foreground execution，方向键/粘贴控制序列继续不得误触发；
- Ctrl+C/UserInterruptException 由 CLI 输入线程处理：有 foreground execution 时请求取消，没有时只清空当前编辑输入；不允许 Worker 修改 terminal attributes；
- Worker 不得裸写 `System.out`。Agent/Plan 的 token streaming 继续走 `Renderer.stream()`，生命周期、交互和队列事件由 `CliUiEventBridge` 串行写 Renderer；`InlineRenderer` 在 LineReader 读取态统一使用 `printAbove`；
- 普通消息 enqueue 后立即重新得到输入 prompt；
- idle Session 的第一条任务会很快 claim；
- busy Session 显示 queue position；
- 第一阶段同 workspace 串行，因此一个 CLI workspace 同时最多一个 streaming Execution；非当前 Session 的结果只发布完成通知，正文通过 `/task log` 查看；
- 不允许多个线程直接 System.out 与 JLine 抢终端。

Control Plane 必须显式分类，不能沿用“输入循环现在空闲所以命令天然安全”的假设：

| 类别 | 命令示例 | RUNNING/ENQUEUED 时行为 |
|---|---|---|
| 只读状态 | `/task list`、`/task log`、`/context`、`/mcp` | 立即执行，但只读取 immutable snapshot |
| Execution 控制 | `/cancel`、`/task cancel`、`/exit` | 立即执行；`/exit` 走 RUNTIME_SHUTDOWN |
| 强制 enqueue | `/task add <text>` | 始终 enqueue，不作为 interaction response |
| Session/上下文变更 | `/clear`、`/compact`、`/new`、`/resume` | 当前 Session 存在非终态 Execution 或 pending interaction 时拒绝，并提示先等待/取消；空闲时在 Registry lease 下执行 |
| Provider/工具配置 | `/config`、模型/provider 切换、会改变 MCP/ToolRegistry 的操作 | 当前 workspace 存在非终态 Execution 时拒绝；空闲时更新 Session binding |
| Workspace/Plan 状态变更 | `/restore`、`/plan resume`、`/plan abandon` | 有非终态 Execution 时拒绝；新 execution-bound Plan 由 Scheduler 自动恢复；`/plan resume` 只承担 legacy active Plan 的确定性 adoption，不直接执行工具 |

命令清单实现时必须以 `CliCommandParser.CommandType` 全量枚举生成测试；未分类的新命令默认 fail closed，而不是运行中直接执行。

### 3.15 Plan Review / HITL：InteractionBroker

Worker 线程不能直接阻塞读终端。

新增 InteractionBroker：

~~~text
Execution Worker
    │ interaction request
    ▼
InteractionBroker
    │
    ▼
CLI Input Loop
    │ user response
    ▼
InteractionBroker
    │
    ▼
Execution Worker resumes
~~~

至少支持：

- Plan execute / supplement / cancel；
- HITL approve / reject。

每个请求必须包含 `interactionId + executionId + sessionId + kind + allowedActions`。第一阶段一个 CLI surface 全局最多一个 pending interaction；Broker 若观察到第二个请求必须 fail closed 并让对应 Execution 失败，不能让用户猜测回复对象。该限制不影响未来带显式 interactionId 的远程 UI。

输入分发：

1. 没有 pending interaction：
   - 普通文本 -> enqueue 下一 Runtime Execution；
2. 存在 pending interaction：
   - 恰好一个 pending interaction 时，普通文本作为该 interaction response；
   - /task add <text> 强制 enqueue 为下一 Execution；
   - control command 继续独立处理。

Plan supplement 可以接收自由文本并调整当前 Plan goal，但不得扩展本 Execution 的 TurnToolPolicy、trusted path 或 trusted URL；如果补充要求需要新的权限来源，用户必须取消并提交新的顶层 Execution。HITL 只接受当前 `allowedActions` 对应的显式 token，批准只作用于该次已展示的工具请求；非法文本只能重新提示，绝不能自动批准、修改参数或静默变成 queued task。Broker 完成、取消、Execution terminal 或 runtime shutdown 时必须清除 pending request，迟到响应按 interactionId 拒绝。

因此 /task add 在新架构中只剩一个实际特殊用途：

> 当前 Execution 正等待人工 review/HITL 时，用户仍想明确追加下一条队列任务。

### 3.16 Cancellation、终态提交与 runtime shutdown

CancellationContext 保持唯一读取入口，但底层改为 execution-scoped token。所有异步边界——CLI worker、Scheduler worker、Plan Task executor、ToolRegistry 并行工具执行——都必须显式 capture/install/clear token；TUI 保留旧入口的兼容适配，但不得再依赖进程级 `CURRENT`。

区分：

~~~text
USER_CANCEL
RUNTIME_SHUTDOWN
~~~

USER_CANCEL：

- ENQUEUED -> CANCELED；
- RUNNING 不立即写 terminal：先以 `WHERE status='running' AND cancel_requested_at IS NULL` 持久化 `cancel_requested_at`，再 token.cancel + best-effort interrupt；
- Worker/Finalizer 观察到 cancel_requested_at 后，无论同时得到何种普通结果，都只允许提交 CANCELED；
- cancel CAS 发现 Execution 已 terminal 时返回“已结束”，不得覆盖既有终态。

RUNTIME_SHUTDOWN：

- Scheduler 先停止 claim 新 Execution；
- 请求 RUNNING execution 协作式停止；
- 不写 CANCELED；
- best-effort 恢复为 ENQUEUED，或留给下次 stale-RUNNING recovery；
- ordinal 不变；
- 重启后继续同 Session 队首。

Worker 结束后必须清除 interrupt 状态，避免固定线程池永久减员。

`ExecutionFinalizer` 是 `execution/end` 与 SQLite terminal 的唯一写入者。单进程约束下，它和 cancel 使用同一个 per-execution lock，并执行：

1. reload RuntimeExecution；
2. 若已 terminal，幂等返回且不写第二个 END；
3. 若 `cancel_requested_at != NULL`，强制选择 CANCELED；
4. 幂等 append `execution/end`；
5. CAS `status='running' -> terminal`；
6. CAS 失败时重新读取并验证 Session END 与 DB terminal 一致，不一致则 fail closed 并记录 recovery error。

若 crash 位于 4 与 5 之间，启动恢复必须在开放 CLI 输入和接受 cancel 前先扫描 Session END 并收敛 SQLite。若 DB 已 terminal 但缺 END，Reconciler 只可根据 DB 中已有 typed outcome 补写同值 END；发现值冲突时不得猜测覆盖。

### 3.17 /task 命令的新定位

保留：

~~~text
/task list [N]
/task cancel <execution_id>
/task log <execution_id>
~~~

语义全部改成 Runtime Execution 管理。

/task add：

- 兼容保留；
- 与普通输入调用同一 enqueue；
- 默认帮助中说明“通常无需使用，直接输入任务即可”；
- pending InteractionBroker 请求时可用来强制追加下一条任务。

### 3.18 数据库迁移

目标 canonical table 名为 runtime_executions，避免继续把顶层 Execution 与 Plan Task 都叫 Task。

迁移由 `runtime_schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)` 驱动，每个版本在一个 SQLite 写事务内可重入执行。第一阶段采用非破坏迁移，不 rename/drop `runtime_tasks`：Repository 切换到 `runtime_executions` 后旧表保留只读，便于回滚和审计。

迁移原则：

1. 检测旧 `runtime_tasks` 与已应用 migration version；
2. `CREATE TABLE/INDEX IF NOT EXISTS runtime_executions`；
3. 用 `INSERT OR IGNORE` 按旧主键复制兼容字段：
   - id；
   - prompt -> submitted_input；
   - status/result/error/timestamps；
4. legacy row 没有可靠 session_id / ordinal：
   - `legacy_unbound=1`，`session_id=NULL`，`ordinal=NULL`；
   - terminal row 允许 list/log；
   - 非 terminal row 不自动执行；
5. status 通过严格 codec 规范化为小写；未知状态使 migration 回滚，不能回退为 enqueued；
6. 缺失 `updated_at` 时只可由该行已有 `finished_at/started_at/created_at` 按顺序 COALESCE，不得使用当前时间改写历史排序；
7. 在同一事务内验证主键集合、字段映射和复制行数，然后记录 migration version；
8. migration 失败整笔回滚并禁用 Execution Scheduler，但 CLI 主界面仍可启动并报告诊断；禁止清空、rename 或删除旧表。

具体 DDL 必须有真实旧 schema fixture、重复运行、复制中途失败回滚、未知状态和旧表保留测试。

### 3.19 权限边界

当前 Execution 的 submitted_input 是唯一顶层权限来源。

历史 Session 即使出现：

- absolute path；
- URL；
- tool result；
- reasoning；
- 旧 HITL approval；

都不能给新 Execution 产生新的 capability。

因此：

- Router / Planner 可以读历史语义；
- ReAct 可以读 Provider Surface；
- TurnToolPolicy 只按当前 Execution raw submitted input 建权限；
- resolved_task_input 不能反向扩权。

Router 的请求固定为当前 Execution 的 raw `submitted_input` + start-time Top-level Conversation；`resolved_task_input`、历史 tool result、Plan supplement 和其它 InteractionBroker 文本都不能替代或扩展 raw input 成为 Router/TurnToolPolicy 的权限输入。HITL approval 只对已展示的具体调用生效，不转化为通用 capability。

---

## 4. 核心时序与状态

### 4.1 运行中追加消息

~~~mermaid
sequenceDiagram
    participant U as User
    participant CLI as CLI Input Loop
    participant DB as Execution Store
    participant W as Worker
    participant S as Parent Session
    participant A as Agent/Plan

    U->>CLI: 消息 A
    CLI->>DB: enqueue E1
    W->>DB: claim E1
    W->>S: execution/start E1
    W->>A: execute E1

    U->>CLI: 消息 B（A 仍运行）
    CLI->>DB: enqueue E2
    CLI-->>U: queued #2

    A-->>W: E1 outcome
    W->>S: execution/end E1
    W->>DB: E1 COMPLETED

    W->>DB: claim E2
    W->>S: context now includes E1 final result
    W->>A: execute E2
~~~

### 4.2 Runtime Execution 状态机

~~~mermaid
stateDiagram-v2
    [*] --> ENQUEUED
    ENQUEUED --> RUNNING: session head + workspace slot + worker slot
    ENQUEUED --> CANCELED: user cancel
    RUNNING --> COMPLETED: SUCCEEDED
    RUNNING --> FAILED: FAILED / REJECTED
    RUNNING --> RUNNING: USER_CANCEL durable request
    RUNNING --> CANCELED: Finalizer observes cancel request
    RUNNING --> ENQUEUED: crash / runtime shutdown recovery
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELED --> [*]
~~~

### 4.3 顶层串行与 Plan 内部并发

~~~text
Current Workspace Execution Worker
├─ Session A: E1 RUNNING
│    └─ 如果 E1=PLAN，内部 Task 最多 4 路并行
└─ Session B: E7 ENQUEUED

Session A:
E1 RUNNING -> E2 ENQUEUED -> E3 ENQUEUED
~~~

Workspace serialization、Session FIFO 与 Plan DAG parallelism 是三个不同层级。第一阶段当前 workspace 只有一个顶层 Worker；Plan DAG 仍可在该 Execution 内有界并行。

---

## 5. 实现任务与测试矩阵

### 5.1 实现顺序

文件边界先固定如下；新类型不得继续堆进 `Main.java` 或旧 `DurableTaskManager`：

| 文件/包 | 职责 |
|---|---|
| `runtime/execution/RuntimeExecution.java`、`ExecutionStatus.java` | immutable row model 与严格小写 status codec |
| `runtime/execution/RuntimeExecutionStore.java` | schema migration、enqueue/ordinal、claim、routing ack、cancel request、terminal CAS、查询 |
| `runtime/execution/RuntimeExecutionQueue.java` | ordinary、`/react`、`/plan`、`/task add` 的唯一 submit API 与查询/取消适配 |
| `runtime/execution/WorkspaceAwareExecutionScheduler.java` | workspace-bound single worker、Session FIFO、recovery、shutdown、token scope |
| `runtime/execution/SessionExecutionContextRegistry.java` | Session runtime context/handle 的创建、lease、驱逐和关闭 |
| `runtime/execution/SessionExecutionContext.java`、`SessionExecutionContextFactory.java` | 每 Session Agent/上下文/handle 所有权与 lazy writable resume |
| `runtime/execution/ExecutionFinalizer.java` | execution/end 与 SQLite terminal 的唯一提交和 reconciliation |
| `runtime/interaction/InteractionBroker.java`、`InteractionRequest.java` | keyed Plan review/HITL transport 与 allowedActions 校验 |
| `runtime/execution/TopLevelExecutionCoordinator.java`、`TopLevelExecutionResult.java` | resolve 后的 route、durable ack、Snapshot、mode dispatch、typed outcome |
| `TopLevelExecutionCoordinator.ExecutionInputResolver` | 无 UI 的输入解析接口；Main 注入既有 mention/path expander，不负责权限判断 |
| `cli/CliUiEventBridge.java` | Worker 生命周期/交互/队列 UI event 串行化；token streaming 复用 Renderer 既有 JLine-safe stream |
| `history/SessionEvent.java`、`SessionReplayer.java`、`SessionProjection.java` | execution envelope required events 与 projection index |
| `agent/Agent.java`、`PlanExecuteAgent.java`、`PlanConversationReconciler.java` | FIRST/RESUME typed entry、唯一顶层 User、executionId metadata |
| `plan/PlanStateStore.java` | `plan_runs.execution_id` migration 与 lineage lookup |
| `cli/Main.java`、`runtime/task/TaskCommandFormatter.java` | 输入循环/Control Plane 接线与 `/task` 兼容 adapter |

对应测试放在相同 package 的 `src/test/java` 下；CLI 分流与控制由 `InteractionInputRouterTest`、`ExecutionControlPolicyTest`、`MainSessionCommandTest` 覆盖，队列时序由 `RuntimeExecutionQueueTest`、`TopLevelExecutionCoordinatorTest`、`WorkspaceAwareExecutionSchedulerTest` 覆盖。旧库 fixture 由 `RuntimeExecutionStoreTest` 在临时目录创建；当前没有独立的 MainExecutionQueueTest 或静态 runtime-execution fixture 目录。

#### Task A：先补目标行为测试

先让当前代码在以下目标上稳定失败：

- 普通输入会 durable enqueue；
- E1 运行时仍能接收 E2；
- 同 workspace 即使不同 Session 也不会并行；
- 当前实例绝不 claim 其它 workspace；
- queued user message 不提前进入 Provider Surface。

#### Task B：typed ReAct / Plan outcome

抽 typed internal API，保留旧 run(): String adapter。

#### Task C：RuntimeExecution Store 与 migration

实现：

- runtime_executions；
- ordinal；
- CAS claim；
- one-running-per-session/workspace；
- strict lowercase status codec；
- cancel_requested_at + terminal CAS；
- legacy migration。

#### Task D：SessionExecutionContextRegistry

实现 Registry、execution lease、唯一 writable SessionHandle、lazy resume、idle eviction 和 EOF/unclosed Session 生命周期；先把 Main 对单例 Agent/activeSession 的直接所有权移入 Registry，再接 Scheduler。

#### Task E：Session Execution Envelope 与 Finalizer

实现：

- execution/start、execution/end 与 projection envelope index；
- executionId conversation metadata；
- ReAct/Plan 顶层 User 唯一写入者与 FIRST/RESUME typed entry；
- Session END -> SQLite reconciliation；
- cancel/complete/fail first-terminal-wins 与 per-execution lock。

#### Task F：ExecutionInputResolver

把 Main 中与 UI 无关的输入解析/展开抽出来，首次 RUNNING 时 resolve 并 durable。Router/TurnToolPolicy 继续使用 raw submittedInput，只有 Agent/Planner delivery 使用 resolved input。

#### Task G：TopLevelExecutionCoordinator

统一：

~~~text
resolve
-> route
-> durable selected mode
-> snapshot
-> ReAct / Plan
-> typed outcome
~~~

#### Task H：WorkspaceAwareExecutionScheduler

实现：

- single bounded top-level worker；
- same-workspace serialization；
- same-session FIFO；
- workspace-bound claim；
- stale RUNNING recovery；
- shutdown；
- execution-scoped cancellation。

#### Task I：Plan execution_id

实现：

- plan_runs.execution_id migration；
- save/replan 绑定 executionId；
- active/terminal/no-plan recovery；
- legacy active Plan deterministic adoption；
- completed Task 不重跑。

#### Task J：CLI 输入 / 执行解耦与 Control Plane

实现：

- input loop 持续 readLine；
- ordinary task enqueue；
- queue position；
- CLI-owned ESC/Ctrl+C；
- thread-safe Renderer bridge；
- 全量 CommandType 并发门禁矩阵。

#### Task K：InteractionBroker

把 PlanReviewHandler / HITL 从 Worker 终端读取改成 keyed broker；第一阶段限制每个 CLI surface 最多一个 pending interaction，并严格校验 allowedActions。

#### Task L：统一 /task 语义

- list/cancel/log -> Runtime Execution；
- add -> enqueue alias；
- README/help 同步。

#### Task M：文档与回归

更新本文实施记录、AGENTS.md、docs/dev/05-runtime-api-tasks.md、README；不得新增第二份 implementation plan。

### 5.2 测试矩阵

| 场景 | 预期 |
|---|---|
| idle 时普通输入 | 先 ENQUEUED，随后 RUNNING |
| E1 运行时输入 E2 | E2 立即 durable ENQUEUED |
| 连续输入 E2/E3 | ordinal 单调递增 |
| E2 queued | E1 运行期间 E2 不进入 Provider Surface |
| E1 完成后 E2 开始 | E2 看到 E1 final context |
| 同 Session | 最多一个 RUNNING，严格按 ordinal |
| 同 workspace 不同 Session | 最多一个 RUNNING，避免 workspace 副作用冲突 |
| 其它 workspace 的 ENQUEUED row | 当前实例不 claim |
| /react payload | explicit_mode=REACT |
| /plan payload | explicit_mode=PLAN |
| /task add payload | 与普通 enqueue 相同 |
| Router failure | AUTO_FALLBACK REACT |
| mode durable 写失败 | Agent 不启动 |
| mode 已存在且相同 | 幂等复用，不 reroute |
| mode 已存在但不同 | fail closed，不启动 Agent |
| ReAct typed failure | Runtime FAILED |
| Plan typed failure | Runtime FAILED |
| ReAct budget partial | Runtime COMPLETED + outcome PARTIAL |
| cancel queued | CANCELED 且不执行 |
| cancel running 与正常完成竞争 | cancel_requested_at/terminal CAS 决定唯一终态，无冲突 END |
| runtime shutdown | 不写 CANCELED，允许恢复 |
| ReAct crash | 同 executionId 走 resume entry，不重复 top-level User |
| E1 crash + E2 queued | E2 不越过 E1 |
| Plan active crash | 按 executionId resume |
| Plan terminal / Runtime 未终态 | 直接收敛，不重新 Planner |
| 同 Session 多个 Plan | lineage 绑定各自 executionId |
| legacy active Plan 无 execution_id | 阻止新 Execution；显式 resume 以 deterministic ID adoption 后交给 Scheduler |
| legacy Plan adoption 跨库中途 crash | 重试补齐 row/bind，不重复创建、不直接执行副作用 |
| HITL pending | 普通输入作为 interaction response |
| HITL pending + /task add x | x 强制 enqueue |
| HITL 非法自由文本 | 重新提示，不批准、不 enqueue |
| Plan supplement 含新 URL/path | 可调整 goal，但不扩展当前 Execution Tool Policy |
| 第二个并发 interaction | fail closed，不出现回复对象歧义 |
| streaming 时用户编辑输入 | UI 不破坏输入行 |
| ESC / Ctrl+C during input | CLI 输入线程路由取消，Worker 不读 terminal |
| Session/context mutating command while nonterminal | 按 Control Plane 矩阵拒绝 |
| EOF 且仍有非终态 Execution | runtime shutdown，Session 保持 unclosed |
| legacy runtime_tasks | 不丢数据；unbound nonterminal 不自动执行 |
| legacy unknown status | migration 回滚并禁用 Scheduler，不回退 ENQUEUED |
| migration 重复执行/中途失败 | 幂等或整笔回滚，旧表保留 |
| 历史 URL/path | 不扩展新 Execution 权限 |
| resolved input 含新增 URL/path | Router/Policy 仍只以 raw submitted input 为准 |

### 5.3 验证命令

实现完成后至少执行：

~~~bash
mvn test -DskipTests=false -Dtest=CliCommandParserTest,ExecutionModeRouterTest,SessionStoreTest
mvn test -DskipTests=false -Dtest=RuntimeExecutionStoreTest,RuntimeExecutionQueueTest,SessionExecutionContextRegistryTest,SessionExecutionContextFactoryTest,SessionExecutionContextTest,WorkspaceAwareExecutionSchedulerTest,TopLevelExecutionCoordinatorTest
mvn test -DskipTests=false -Dtest=PlanExecuteRecoveryTest,PlanStateStoreTest,MainPlanAgentFactoryTest
mvn test -DskipTests=false -Dtest=CancellationContextTest,InteractionBrokerTest,BrokerInteractionHandlersTest,InteractionInputRouterTest,ExecutionControlPolicyTest,MainSessionCommandTest,MainInputNormalizationTest
mvn test -Pquick
mvn test -DskipTests=false
mvn clean package
git diff --check
~~~

最终测试类名以实际实现为准，并把真实结果回填本文。

---

## 6. 风险与边界

### 6.1 Queue 不等于 mid-turn steering

新消息进入下一 Execution，不改变当前执行中的模型指令。

未来若要支持真正“打断并修改当前任务”，应单独设计 steer / interrupt-and-replan。

### 6.2 同 workspace 串行与 Session FIFO 是正确性约束

用于保护：

- ParentConversationContext；
- Provider Surface 顺序；
- Top-level Conversation；
- workspace side effects；
- active Plan uniqueness；
- 用户对话因果顺序。

Session FIFO 保护对话因果关系，但不能隔离文件系统。第一阶段必须同时满足：同 Session 按 ordinal 串行、当前 workspace 顶层互斥、当前实例不 claim 其它 workspace。未来只有引入 lease/owner 和跨 Execution ResourceClaims 或隔离 workspace 后才能放宽。

### 6.3 ReAct 仍是 at-least-once

统一 Execution identity 后可以：

- 知道哪个 Turn 中断；
- 保证 queued Turn 不越过；
- 避免把同一用户输入重复创建成新 Turn。

但第一阶段仍不能保证已经发生的外部工具副作用绝不重复。

### 6.4 CLI 并发输入输出是高风险点

必须专门覆盖：

- Worker streaming 时用户编辑输入；
- queue status；
- Plan review；
- HITL；
- Ctrl+C / ESC；
- Windows terminal。

不允许用多线程裸 System.out 绕过 Renderer。CLI Input Loop 是唯一输入所有者，Renderer 是唯一终端输出 surface；CliUiEventBridge 负责生命周期/交互/队列事件，Agent/Plan streaming 复用 Renderer.stream()。Worker 不得进入 raw mode 或调用 LineReader。

### 6.5 InteractionBroker 不能绕过安全门禁

Broker 只是 transport。

它不能：

- 自动 approve；
- 把 queued task 当 approve；
- 把 policy denied 升级为 HITL；
- 修改 TurnToolPolicy -> HITL -> ToolRegistry 顺序。

### 6.6 migration 风险

runtime_tasks -> runtime_executions 必须用旧 schema fixture 验证。

失败时 fail closed，不能丢历史任务数据。

### 6.7 Scope

第一阶段只保证 inline/plain CLI。

Runtime HTTP API、TUI 后续可提交到同一 Execution Store，但本次不强行一起重写。

因为 `CancellationContext` 是共享基础设施，本次仍必须给 TUI 保留兼容适配并运行回归测试；“不接入 Queue”不等于允许其取消语义损坏。

---

## 7. 实施记录

### 7.1 分支

~~~text
refactor/unified-durable-execution-runtime
~~~

基于：

~~~text
main@33c6a24caf3835835c62b2cd36c9c710afb7bdc8
~~~

### 7.2 设计演进

第一版：

~~~text
普通消息 -> 现有同步 ReAct/Plan
/task add -> durable background queue
~~~

第二版：

~~~text
/task add -> 独立 Background Session -> Router
~~~

再次 review 后确认仍然割裂，因此当前最终方向改为：

~~~text
所有普通顶层任务
      ↓
durable Runtime Execution Queue
      ↓
Workspace-aware Scheduler
      ↓
Mode Router
      ↓
ReAct / Plan
~~~

/task add 不再代表另一类任务，只是同一 enqueue API 的兼容命令。

### 7.3 当前阶段

实现已进入回归阶段。当前已落地：

- `runtime_executions` schema、严格状态 codec、Session ordinal、workspace-bound claim、取消竞争和旧 `runtime_tasks` 非破坏迁移；
- ordinary / `/react` / `/plan` / `/task add` 共用 `RuntimeExecutionQueue.submit`，inline/plain 输入线程不再等待 Agent 返回；
- `WorkspaceAwareExecutionScheduler` 单 Worker、workspace 串行、Session FIFO、stale RUNNING recovery 和 `RUNTIME_SHUTDOWN` 回排；
- ReAct / Plan typed outcome、ExecutionInputResolver 时序、durable routing ack、Snapshot 边界；
- `execution/start` / `execution/end`、projection envelope index、Finalizer first-terminal-wins 和启动期 END -> SQLite 收敛；
- Plan `execution_id` lineage、按 execution 恢复已有 DAG、legacy active Plan 确定性收养与 claim gate；
- execution-scoped `CancellationContext`、InteractionBroker、Broker PlanReview/HITL handler、allowedActions 输入分流；
- `SessionExecutionContextFactory/Registry` 已接入 Main：启动 Session 直接收养，其他 Session lazy resume；Scheduler 按 execution.sessionId 取得 lease，`/resume`、`/new`、idle eviction、EOF 与 shutdown 均由 Registry 管理 writable handle；
- 全量 CommandType 运行中分类、`/task list|add|cancel|log` 兼容适配、README/AGENTS/旧 Runtime 文档状态说明。

当前适用范围与验证限制：

- `CliUiEventBridge` 已串行化队列、状态和交互提示；Agent/Plan streaming 复用 `Renderer.stream()`，`InlineRendererTest` 已验证 LineReader 读取态改走 `printAbove`。真实 Windows inline 终端的人工作业验收仍建议保留；
- Runtime HTTP API、Lanterna TUI 按 scope 未接队列，只做共享取消语义回归；
- terminal Plan / Runtime 非终态窗口已补齐：按 `execution_id` 读取终态 lineage 并直接映射 typed outcome，不重新 Planner 或调用 LLM；
- 早期提交阶段 quick、全量和干净构建通过记录保留在 7.4。最新 package 成功，clean 受 target/classes 删除失败阻塞，尚不能声称当前 clean package 已通过。

### 7.4 实施验证记录

以下数字是各阶段实际执行记录；最终状态以本节最后的 Session 接线、清理及环境限制为准。文档联动已同步 README、AGENTS、CLAUDE、ROADMAP、agents-reference 与旧 Runtime 分析；补充直接输入排队、审批分流、Registry 所有权、共享绑定互斥及 scope，并核对实际类名/测试名。此次联动仅改文档，验证采用源码对照、相对链接/文件存在性检查和 git diff --check。

代码清理：删除 Main 中已被统一队列替代、无调用者的 `openTaskManager` 私有方法和 Coordinator 的未使用 `RoutingSource` import；保留 Runtime API 使用的 `runHeadlessTask` 及有明确兼容说明的方法。三个交互测试类统一使用 try-with-resources 关闭 InteractionBroker，两个异步测试通过 finally 释放 ExecutorService，确保断言失败也能清理。目标为移除已确认的死代码与资源关闭警告，不改变执行队列和交互协议；针对性测试 15 项全部通过。`git diff --check` 通过。

- `RuntimeExecutionStoreTest,RuntimeExecutionQueueTest,WorkspaceAwareExecutionSchedulerTest,PlanStateStoreTest,PlanExecuteRecoveryTest,MainPlanAgentFactoryTest`：39 tests，全部通过；
- `RuntimeExecutionStoreTest,WorkspaceAwareExecutionSchedulerTest,TopLevelExecutionCoordinatorTest,RuntimeExecutionTaskCommandFormatterTest,InteractionBrokerTest,BrokerInteractionHandlersTest,InteractionInputRouterTest,ExecutionControlPolicyTest`：25 tests，全部通过；
- `RuntimeExecutionStoreTest,RuntimeExecutionQueueTest,RuntimeExecutionTaskCommandFormatterTest,SessionExecutionEnvelopeTest,PlanExecuteAgentTest,PlanConversationReconcilerTest,AgentSessionResumeTest`：34 tests，0 failure / 0 error / 1 skipped；
- 干净重建 `LspDiagnosticFormatterTest,LspManagerTest,AgentLspDiagnosticsTest,PlanDiffEvidenceIntegrationTest`：7 tests，全部通过。
- `PlanExecuteRecoveryTest,PlanStateStoreTest,SessionExecutionContextRegistryTest,InlineRendererTest,CancellationContextTest,WechatRendererTest`：58 tests，全部通过；其中终态 Plan 收敛测试确认不重新 Planner、不调用 LLM；
- `mvn test -Pquick`：1197 tests，0 failure / 0 error / 4 skipped；
- `mvn test -DskipTests=false`：1255 tests，0 failure / 0 error / 10 skipped；
- `mvn test -Pphase16-smoke`：106 tests，0 failure / 0 error / 0 skipped；覆盖 inline/plain renderer、HITL、输入规范化和 TUI bootstrap；全量测试同时覆盖 Runtime API 回归；
- `mvn clean package -DskipTests`：`BUILD SUCCESS`（shade 插件仅报告既有重复资源 warning）。
- `git diff --check`：exit 0；仅有仓库既有的 LF -> CRLF 提示。
- Session Context 最终接线后，`SessionExecutionContextRegistryTest,SessionExecutionContextFactoryTest,SessionExecutionContextTest,MainSessionCommandTest,WorkspaceAwareExecutionSchedulerTest,RuntimeExecutionQueueTest,ExecutionFinalizerTest,ExecutionControlPolicyTest,MainExecutionModeRoutingTest,MainPlanAgentFactoryTest`：26 tests，全部通过；覆盖启动 Context 收养、lazy resume、跨 Session 全局 lease、失败释放 writable handle、idle eviction、CLI mutation 分类与 Main 路由回归；
- 最终 `mvn test -Pphase16-smoke`：106 tests，全部通过；`mvn package -DskipTests`：`BUILD SUCCESS`。`mvn clean package -DskipTests` 未进入编译，因 Windows 删除 `target/classes` 失败；观察到 VS Code Java 语言服务进程，但尚未证明具体占用来源，未终止用户进程。

---

## 8. 验收清单

- [x] inline/plain CLI 普通顶层任务全部先创建 durable Runtime Execution。
- [x] ordinary input、/react payload、/plan payload、/task add payload 走同一 enqueue API。
- [x] /task add 不再创建独立 Background Session 或 fork。
- [x] Agent 运行时 CLI 仍能接收下一条普通消息。
- [x] queued message 在真正开始前不会进入 Provider Surface / Top-level Conversation。
- [x] queued Execution 开始时能看到前序 Execution 的最终 Session context。
- [x] 同 Session 任意时刻最多一个 RUNNING Execution。
- [x] 同 workspace 即使不同 Session 也最多一个 RUNNING Execution。
- [x] 当前实例不 claim 其它 workspace 的 Execution；顶层 executor 固定为单 Worker。
- [x] SessionExecutionContextRegistry 独占 writable SessionHandle/Agent/ParentConversationContext 生命周期。
- [x] EOF 时非终态 Execution 不被误写 CANCELED，相关 Session 不被提前关闭。
- [x] Runtime Execution 与 Plan Task 保持不同实体。
- [x] canonical store 使用 RuntimeExecution / runtime_executions 语义。
- [x] selected mode durable ack 在 ReAct/Plan 副作用之前。
- [x] selected mode CAS 支持同值幂等、异值 fail closed。
- [x] ReAct / Plan 使用 typed ExecutionOutcome。
- [x] partial、failed、rejected、user cancel、runtime shutdown 的映射无歧义。
- [x] 错误文本不会被误标为 COMPLETED。
- [x] Session Event Log 有 executionId 边界。
- [x] ReAct/Plan 各有唯一顶层 User 写入者，crash retry 不重复 append 顶层 User。
- [x] ReAct interrupted Execution 阻塞后续同 Session queued Execution。
- [x] Plan Run 绑定 execution_id。
- [x] active Plan 按 execution_id 恢复。
- [x] terminal Plan 可收敛 Runtime Execution。
- [x] legacy active Plan 在 deterministic adoption 前阻止新 Execution，adoption crash 可收敛且不触发工具副作用。
- [x] Plan/HITL 通过 InteractionBroker 与 CLI 交互。
- [x] pending interaction 时普通输入不会被误当 queued task；/task add 可强制 enqueue。
- [x] InteractionBroker 请求带 interactionId/executionId/sessionId/allowedActions，且第一阶段每个 CLI surface 最多一个 pending。
- [x] HITL 非法文本不会被解释为批准或 queued task。
- [x] CancellationContext 成为唯一 execution-scoped 取消读取入口。
- [x] RUNNING cancel 先 durable cancel_requested_at，再由 Finalizer 提交唯一 CANCELED 终态。
- [x] execution/end 与 SQLite terminal 冲突时 fail closed，不猜测覆盖。
- [x] USER_CANCEL 与 RUNTIME_SHUTDOWN 语义分离。
- [x] Worker interrupt 不导致固定线程池永久减员。
- [x] Tool Policy 只以当前 Execution raw submitted input 为权限来源。
- [x] Router 只读取 raw submitted input，不读取 resolved task input 作为当前输入。
- [x] CLI Input Loop 是唯一终端输入所有者，Worker 不读 LineReader/raw terminal。
- [x] Renderer 是唯一终端输出 surface；CliUiEventBridge 串行化 lifecycle/interaction/queue event，Agent/Plan streaming 经 Renderer.stream() 且 inline 读取态使用 printAbove。
- [x] 所有 CliCommandParser CommandType 都有运行中 Control Plane 分类，未知新命令默认 fail closed。
- [x] legacy runtime_tasks migration 有旧 schema 测试且失败不丢数据。
- [x] migration 可重入、未知状态回滚、旧表保留；legacy-unbound row 使用 NULL session/ordinal 且永不 claim。
- [x] Runtime HTTP API / TUI 未被本次重构意外回归。
- [x] 针对性测试通过。
- [x] 全量验证范围与限制已记录；最新无排除全量尚未通过验收，不能将排除测试的回归称为完整通过。
- [x] 构建通过。
- [x] git diff --check 通过。
- [x] 本文是本任务唯一 docs/dev 设计与实施文档。
