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
2. 同一个 Session 内的顶层 Execution 严格串行：
   - Execution N 未终态时，Execution N+1 只能 ENQUEUED；
   - N+1 真正开始时读取 N 已提交的最新 Session 上下文。
3. 不同 Session 的 Execution 可以由固定大小 Worker Pool 并行执行。
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
- 不允许同一个 Session 同时运行两个顶层 Execution。
- 不改变 Plan 内部最多 4 路无冲突 Task 并行、ResourceClaims、Evidence Gate、Reviewer。
- 不实现多进程 lease、heartbeat、priority、dead-letter、exactly-once。
- 不一次性迁移 Runtime HTTP API、WeChat、Lanterna TUI；第一阶段只改目前有 Auto Router 的 inline/plain CLI。
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

统一 Queue 后，不同 Session 的 Execution 会并行；Plan 内还会创建 child worker。

全局 CURRENT + InheritableThreadLocal 不能作为可靠并发传播模型，必须改成显式 execution scope。

---

## 3. 方案设计

### 3.1 总体架构

~~~mermaid
flowchart TB
    I[CLI Input Loop] --> D{Control or Task Input}

    D -->|control command| CTRL[Control Plane]
    D -->|ordinary task| SUB[ExecutionSubmissionService]
    D -->|/react payload| SUB
    D -->|/plan payload| SUB
    D -->|/task add payload| SUB

    SUB --> DB[(runtime_executions)]
    DB --> SCH[SessionAwareExecutionScheduler]

    SCH --> W1[Execution Worker]
    SCH --> W2[Execution Worker]

    W1 --> C[TopLevelExecutionCoordinator]
    W2 --> C

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
2. 同 Session 顶层严格串行，不同 Session 可以并行。
3. ReAct / Plan 是 Execution 的 mode，不是 submission type。

### 3.2 顶层模型 RuntimeExecution

建议模型：

~~~java
record RuntimeExecution(
    String id,
    String workspace,
    String sessionId,
    long ordinal,
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
    session_id          TEXT NOT NULL,
    ordinal             INTEGER NOT NULL,
    status              TEXT NOT NULL,
    submitted_input     TEXT NOT NULL,
    resolved_task_input TEXT,
    explicit_mode       TEXT,
    selected_mode       TEXT,
    routing_source      TEXT,
    outcome             TEXT,
    result              TEXT,
    error               TEXT,
    attempt             INTEGER NOT NULL DEFAULT 0,
    interruption_reason TEXT,
    created_at          TEXT NOT NULL,
    started_at          TEXT,
    finished_at         TEXT,
    updated_at          TEXT NOT NULL,
    UNIQUE(session_id, ordinal)
);
~~~

索引：

~~~sql
CREATE INDEX idx_runtime_executions_queue
ON runtime_executions(workspace, status, created_at);

CREATE UNIQUE INDEX idx_runtime_executions_one_running_per_session
ON runtime_executions(session_id)
WHERE status = 'RUNNING';
~~~

### 3.4 所有普通输入统一 enqueue

普通输入：

~~~text
> 帮我重构支付模块
~~~

执行：

~~~text
ExecutionSubmissionService.enqueue(
  currentSessionId,
  workspace,
  submittedInput,
  explicitMode = null
)
~~~

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

### 3.5 同 Session FIFO，跨 Session 并行

规则：

1. enqueue 时为该 Session 分配单调递增 ordinal；
2. 每个 Session 最多一个 RUNNING；
3. 只有该 Session 最小 ordinal 的 ENQUEUED Execution 可被领取；
4. Worker Pool 上限控制同时活跃的 Session 数；
5. Plan Execution 内部仍可最多 4 个无冲突 Task 并行。

概念 claim：

~~~sql
SELECT e.*
FROM runtime_executions e
WHERE e.workspace = ?
  AND e.status = 'ENQUEUED'
  AND NOT EXISTS (
      SELECT 1 FROM runtime_executions r
      WHERE r.session_id = e.session_id
        AND r.status = 'RUNNING'
  )
  AND NOT EXISTS (
      SELECT 1 FROM runtime_executions p
      WHERE p.session_id = e.session_id
        AND p.status = 'ENQUEUED'
        AND p.ordinal < e.ordinal
  )
ORDER BY e.created_at ASC
LIMIT 1;
~~~

然后 CAS：

~~~sql
UPDATE runtime_executions
SET status='RUNNING', ...
WHERE id=? AND status='ENQUEUED';
~~~

示例：

~~~text
Session A: A1 RUNNING -> A2 ENQUEUED -> A3 ENQUEUED
Session B: B1 RUNNING -> B2 ENQUEUED
Session C: C1 RUNNING
~~~

A1/B1/C1 可并行；A2 必须等待 A1。

### 3.6 上下文语义：start-time continuation

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
4. append E2 user；
5. 执行。

因此 E2 能看到 E1 的最终结果，而不是提交瞬间的中间状态。

### 3.7 输入解析的 durable 边界

普通 CLI 当前有 mention/path/resource expansion。

为了保证 E2 在 E1 完成后再解析，并保证 crash retry 输入一致：

- enqueue 只存 submitted_input；
- Execution 首次 RUNNING 时调用统一 ExecutionInputResolver；
- resolver 成功后 durable 写 resolved_task_input；
- Router / Agent 从此使用 resolved_task_input；
- retry 若字段已存在则直接复用，不重新解析；
- TurnToolPolicy 始终只看 submitted_input。

这要求把 Main 中与 UI 无关的输入展开抽成可复用组件。

### 3.8 Session Execution Envelope

新增通用事件：

~~~text
EXECUTION_START
  executionId
  ordinal
  explicitMode

EXECUTION_END
  executionId
  selectedMode
  outcome
  status
~~~

顶层 USER / ASSISTANT conversation metadata 增加 executionId。

规则：

- claim 后先确保 EXECUTION_START；
- 第一次执行才 append 顶层 User；
- crash retry 发现同 executionId 已有 User 时不重复 append；
- typed terminal outcome 先写 EXECUTION_END，再更新 SQLite terminal；
- Session 已有 END、SQLite 仍 RUNNING 时，恢复阶段从 Session 收敛 SQLite，不重新执行。

Plan 自己的 TURN_START / TURN_END 可以保留，但必须能关联 executionId。

### 3.9 TopLevelExecutionCoordinator

职责：

1. 取得 RuntimeExecution；
2. 确保 resolved_task_input 已 durable；
3. 取得当前 Session Top-level Conversation；
4. explicit override 或 Auto Router；
5. selected_mode / routing_source durable ack；
6. 只有 ack 成功后才允许开始 ReAct / Plan 副作用；
7. SnapshotService.runTurn；
8. 返回 typed outcome。

建议：

~~~java
enum ExecutionOutcome {
    SUCCEEDED,
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
FAILED    -> FAILED
CANCELED  -> CANCELED
REJECTED  -> FAILED + reason
~~~

禁止根据“❌”等展示文本判断状态。

### 3.10 Plan 与 Runtime Execution 绑定

plan_runs 增加：

~~~text
execution_id TEXT
~~~

新 Plan 必须绑定：

~~~text
workspace
session_id
execution_id
~~~

如果发生 replan，新 plan_id 继续属于同一个 execution_id。

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

### 3.11 ReAct crash recovery

第一阶段不实现工具副作用 exactly-once。

恢复规则：

1. startup 发现 stale RUNNING；
2. 若 Session 已存在同 executionId 的 EXECUTION_END：
   - 直接收敛 SQLite terminal；
3. 否则把该 Execution 恢复为 ENQUEUED，ordinal 不变；
4. 下一次 claim 不重复 append 顶层 User；
5. SessionStore 先做 incomplete request / pending tool reconciliation；
6. ReAct 在同 Execution identity 下继续一次恢复执行；
7. 后续 queued Execution 不能越过它。

这仍是 at-least-once：已经完成的外部 side effect 可能重复，恢复提示必须要求模型检查 workspace / external state。

### 3.12 Plan crash recovery

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

### 3.13 CLI 输入与执行解耦

目标：

~~~text
Main CLI Thread
  ├─ 持续 readLine
  ├─ 处理 control command
  ├─ enqueue ordinary task
  └─ 处理 InteractionBroker response

Execution Worker
  └─ Router / ReAct / Plan

UI Event Bridge
  └─ 安全输出 stream / status / queue state
~~~

要求：

- Worker 不直接 readLine；
- Worker 输出经 thread-safe Renderer/UI bridge；
- 普通消息 enqueue 后立即重新得到输入 prompt；
- idle Session 的第一条任务会很快 claim；
- busy Session 显示 queue position；
- 不允许多个线程直接 System.out 与 JLine 抢终端。

### 3.14 Plan Review / HITL：InteractionBroker

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

输入分发：

1. 没有 pending interaction：
   - 普通文本 -> enqueue 下一 Runtime Execution；
2. 存在 pending interaction：
   - 普通文本优先作为 interaction response；
   - /task add <text> 强制 enqueue 为下一 Execution；
   - control command 继续独立处理。

因此 /task add 在新架构中只剩一个实际特殊用途：

> 当前 Execution 正等待人工 review/HITL 时，用户仍想明确追加下一条队列任务。

### 3.15 Cancellation 与 runtime shutdown

CancellationContext 保持唯一读取入口，但底层改为 execution-scoped token。

区分：

~~~text
USER_CANCEL
RUNTIME_SHUTDOWN
~~~

USER_CANCEL：

- ENQUEUED -> CANCELED；
- RUNNING -> token.cancel + best-effort interrupt -> CANCELED。

RUNTIME_SHUTDOWN：

- Scheduler 先停止 claim 新 Execution；
- 请求 RUNNING execution 协作式停止；
- 不写 CANCELED；
- best-effort 恢复为 ENQUEUED，或留给下次 stale-RUNNING recovery；
- ordinal 不变；
- 重启后继续同 Session 队首。

Worker 结束后必须清除 interrupt 状态，避免固定线程池永久减员。

### 3.16 /task 命令的新定位

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

### 3.17 数据库迁移

目标 canonical table 名为 runtime_executions，避免继续把顶层 Execution 与 Plan Task 都叫 Task。

迁移原则：

1. 检测旧 runtime_tasks；
2. 创建新 runtime_executions；
3. 把兼容字段复制过去：
   - id；
   - prompt -> submitted_input；
   - status/result/error/timestamps；
4. legacy row 没有可靠 session_id / ordinal：
   - 标记 legacy-unbound；
   - terminal row 允许 list/log；
   - 非 terminal row 不自动执行；
5. 验证 row count 和主键后，再完成表切换；
6. migration 失败必须 fail closed，不能清空旧 DB。

具体 DDL 必须有真实旧 schema fixture 测试。

### 3.18 权限边界

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
    W->>S: EXECUTION_START E1
    W->>A: execute E1

    U->>CLI: 消息 B（A 仍运行）
    CLI->>DB: enqueue E2
    CLI-->>U: queued #2

    A-->>W: E1 outcome
    W->>S: EXECUTION_END E1
    W->>DB: E1 COMPLETED

    W->>DB: claim E2
    W->>S: context now includes E1 final result
    W->>A: execute E2
~~~

### 4.2 Runtime Execution 状态机

~~~mermaid
stateDiagram-v2
    [*] --> ENQUEUED
    ENQUEUED --> RUNNING: session head + worker slot
    ENQUEUED --> CANCELED: user cancel
    RUNNING --> COMPLETED: SUCCEEDED
    RUNNING --> FAILED: FAILED / REJECTED
    RUNNING --> CANCELED: USER_CANCEL
    RUNNING --> ENQUEUED: crash / runtime shutdown recovery
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELED --> [*]
~~~

### 4.3 双层并发

~~~text
Global Execution Worker Pool
├─ Session A: E1 RUNNING
│    └─ 如果 E1=PLAN，内部 Task 最多 4 路并行
├─ Session B: E7 RUNNING
└─ Session C: E4 RUNNING

Session A:
E1 RUNNING -> E2 ENQUEUED -> E3 ENQUEUED
~~~

Session serialization 与 Plan DAG parallelism 是不同层级的并发。

---

## 5. 实现任务与测试矩阵

### 5.1 实现顺序

#### Task A：先补目标行为测试

先让当前代码在以下目标上稳定失败：

- 普通输入会 durable enqueue；
- E1 运行时仍能接收 E2；
- 同 Session 不会并行；
- 不同 Session 可并行；
- queued user message 不提前进入 Provider Surface。

#### Task B：typed ReAct / Plan outcome

抽 typed internal API，保留旧 run(): String adapter。

#### Task C：RuntimeExecution Store 与 migration

实现：

- runtime_executions；
- ordinal；
- CAS claim；
- one-running-per-session；
- legacy migration。

#### Task D：Session Execution Envelope

实现：

- EXECUTION_START / END；
- executionId conversation metadata；
- crash retry 不重复 append top-level User；
- Session END -> SQLite reconciliation。

#### Task E：ExecutionInputResolver

把 Main 中与 UI 无关的输入解析/展开抽出来，首次 RUNNING 时 resolve 并 durable。

#### Task F：TopLevelExecutionCoordinator

统一：

~~~text
resolve
-> route
-> durable selected mode
-> snapshot
-> ReAct / Plan
-> typed outcome
~~~

#### Task G：SessionAwareExecutionScheduler

实现：

- fixed Worker Pool；
- same-session FIFO；
- cross-session parallel；
- stale RUNNING recovery；
- shutdown；
- execution-scoped cancellation。

#### Task H：Plan execution_id

实现：

- plan_runs.execution_id migration；
- save/replan 绑定 executionId；
- active/terminal/no-plan recovery；
- completed Task 不重跑。

#### Task I：CLI 输入 / 执行解耦

实现：

- input loop 持续 readLine；
- ordinary task enqueue；
- queue position；
- thread-safe Renderer bridge。

#### Task J：InteractionBroker

把 PlanReviewHandler / HITL 从 Worker 终端读取改成 broker。

#### Task K：统一 /task 语义

- list/cancel/log -> Runtime Execution；
- add -> enqueue alias；
- README/help 同步。

#### Task L：文档与回归

更新本文实施记录、AGENTS.md、docs/dev/05-runtime-api-tasks.md、README；不得新增第二份 implementation plan。

### 5.2 测试矩阵

| 场景 | 预期 |
|---|---|
| idle 时普通输入 | 先 ENQUEUED，随后 RUNNING |
| E1 运行时输入 E2 | E2 立即 durable ENQUEUED |
| 连续输入 E2/E3 | ordinal 单调递增 |
| E2 queued | E1 运行期间 E2 不进入 Provider Surface |
| E1 完成后 E2 开始 | E2 看到 E1 final context |
| 同 Session | 最多一个 RUNNING |
| 不同 Session | 可由 Worker Pool 并行 |
| /react payload | explicit_mode=REACT |
| /plan payload | explicit_mode=PLAN |
| /task add payload | 与普通 enqueue 相同 |
| Router failure | AUTO_FALLBACK REACT |
| mode durable 写失败 | Agent 不启动 |
| ReAct typed failure | Runtime FAILED |
| Plan typed failure | Runtime FAILED |
| cancel queued | CANCELED 且不执行 |
| cancel running | token 传播 |
| runtime shutdown | 不写 CANCELED，允许恢复 |
| ReAct crash | 同 executionId 恢复，不重复 top-level User |
| E1 crash + E2 queued | E2 不越过 E1 |
| Plan active crash | 按 executionId resume |
| Plan terminal / Runtime 未终态 | 直接收敛，不重新 Planner |
| 同 Session 多个 Plan | lineage 绑定各自 executionId |
| HITL pending | 普通输入作为 interaction response |
| HITL pending + /task add x | x 强制 enqueue |
| streaming 时用户编辑输入 | UI 不破坏输入行 |
| legacy runtime_tasks | 不丢数据；unbound nonterminal 不自动执行 |
| 历史 URL/path | 不扩展新 Execution 权限 |

### 5.3 验证命令

实现完成后至少执行：

~~~bash
mvn test -DskipTests=false -Dtest=CliCommandParserTest,ExecutionModeRouterTest,SessionStoreTest
mvn test -DskipTests=false -Dtest=RuntimeExecutionStoreTest,SessionAwareExecutionSchedulerTest,TopLevelExecutionCoordinatorTest
mvn test -DskipTests=false -Dtest=PlanExecuteRecoveryTest,PlanStateStoreTest,MainPlanAgentFactoryTest
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

### 6.2 同 Session 串行是正确性约束

用于保护：

- ParentConversationContext；
- Provider Surface 顺序；
- Top-level Conversation；
- workspace side effects；
- active Plan uniqueness；
- 用户对话因果顺序。

不能为了吞吐把同 Session 两个顶层 Execution 并行。

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

不允许用多线程裸 System.out 绕过 Renderer。

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

Runtime HTTP API、WeChat、TUI 后续可提交到同一 Execution Store，但本次不强行一起重写。

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
Session-aware Scheduler
      ↓
Mode Router
      ↓
ReAct / Plan
~~~

/task add 不再代表另一类任务，只是同一 enqueue API 的兼容命令。

### 7.3 当前阶段

当前只修改设计文档，尚未修改 Java 源码和测试。

### 7.4 已确认源码事实

- 普通 CLI 输入当前在执行期间不会继续读取下一条普通消息；
- 普通输入当前不进入 runtime_tasks；
- /task add 当前绕过 Auto Router；
- DurableTaskManager 已有 SQLite queue、固定 Worker、claim、cancel、stale RUNNING recovery；
- ReAct / Plan 已共享 ParentConversationContext；
- Plan 已有 PlanStateStore 和 DAG node recovery；
- PlanStateStore 当前无 execution_id；
- Agent / Plan public run API 仍主要返回 String；
- 当前 CancellationContext 不适合跨 Session 并发 execution。

---

## 8. 验收清单

- [ ] inline/plain CLI 普通顶层任务全部先创建 durable Runtime Execution。
- [ ] ordinary input、/react payload、/plan payload、/task add payload 走同一 enqueue API。
- [ ] /task add 不再创建独立 Background Session 或 fork。
- [ ] Agent 运行时 CLI 仍能接收下一条普通消息。
- [ ] queued message 在真正开始前不会进入 Provider Surface / Top-level Conversation。
- [ ] queued Execution 开始时能看到前序 Execution 的最终 Session context。
- [ ] 同 Session 任意时刻最多一个 RUNNING Execution。
- [ ] 不同 Session 可由固定 Worker Pool 并行。
- [ ] Runtime Execution 与 Plan Task 保持不同实体。
- [ ] canonical store 使用 RuntimeExecution / runtime_executions 语义。
- [ ] selected mode durable ack 在 ReAct/Plan 副作用之前。
- [ ] ReAct / Plan 使用 typed ExecutionOutcome。
- [ ] 错误文本不会被误标为 COMPLETED。
- [ ] Session Event Log 有 executionId 边界。
- [ ] ReAct crash retry 不重复 append 顶层 User。
- [ ] ReAct interrupted Execution 阻塞后续同 Session queued Execution。
- [ ] Plan Run 绑定 execution_id。
- [ ] active Plan 按 execution_id 恢复。
- [ ] terminal Plan 可收敛 Runtime Execution。
- [ ] Plan/HITL 通过 InteractionBroker 与 CLI 交互。
- [ ] pending interaction 时普通输入不会被误当 queued task；/task add 可强制 enqueue。
- [ ] CancellationContext 成为唯一 execution-scoped 取消读取入口。
- [ ] USER_CANCEL 与 RUNTIME_SHUTDOWN 语义分离。
- [ ] Worker interrupt 不导致固定线程池永久减员。
- [ ] Tool Policy 只以当前 Execution raw submitted input 为权限来源。
- [ ] legacy runtime_tasks migration 有 fixture 测试且失败不丢数据。
- [ ] Runtime HTTP API / WeChat / TUI 未被本次重构意外回归。
- [ ] 针对性测试通过。
- [ ] mvn test -Pquick 通过。
- [ ] 全量测试通过。
- [ ] 构建通过。
- [ ] git diff --check 通过。
- [ ] 本文是本任务唯一 docs/dev 设计与实施文档。
