# Plan-and-Execute DAG 任务编排

## 1. 功能定位

Plan-and-Execute 是 CodeAgent 在 ReAct 之外提供的第二种执行范式：先把一次复杂目标交给模型拆解成带依赖关系的 `Task` 集合，形成一个**有向无环图（DAG）**，再用显式调度器按依赖推进。`Planner` 把自然语言目标转成结构化计划，负责 JSON 清洗、任务 ID 规范化与依赖映射；`ExecutionPlan` 是图聚合根，负责拓扑排序、环检测、可执行集合与进度计算；`PlanExecuteAgent` 负责计划审阅、批次调度、单任务内部的多轮 Tool Calling 循环，以及最终结果汇总。与 ReAct「走一步看一步」不同，这里的任务边界在执行前已被固定，因此可以提前展示、并行调度和按依赖汇聚。

- 源码入口：`PlanExecuteAgent.run(String, String)` — `src/main/java/com/codeagent/agent/PlanExecuteAgent.java:256`
- 主调度循环：`PlanExecuteAgent.executePlan(ExecutionPlan, StreamState)` — `PlanExecuteAgent.java:331`
- 计划生成：`Planner.createPlan(String)` — `src/main/java/com/codeagent/plan/Planner.java:59`
- 拓扑排序：`ExecutionPlan.computeExecutionOrder()` — `src/main/java/com/codeagent/plan/ExecutionPlan.java:94`

进入方式（当前只有两条交互路径会真正走到 DAG）：

| 入口 | 行为 | 源码位置 |
|---|---|---|
| CLI `/plan`（无参数） | 设「下一个任务用计划模式」flag，下一条输入触发 | `src/main/java/com/codeagent/cli/Main.java:561-568`、`Main.java:902-911` |
| CLI `/plan <任务>` | 立即以计划模式执行该任务 | `Main.java:561-568` → `input = payload()` 后落入 `Main.java:902` |
| TUI `/plan <任务>` | 立即以计划模式执行，**固定自动审阅（EXECUTE），不弹交互审阅** | `src/main/java/com/codeagent/tui/TuiSessionController.java:211-224`、`TuiSessionController.java:264-273` |
| Runtime API / DurableTask | **绕过 DAG**，走 `runHeadlessTask` → 普通 `Agent`（ReAct） | `Main.java:1004-1015`、`Main.java:969`、`Main.java:1019` |

## 2. 设计意图

### 2.1 ReAct 在复杂任务中的局限

ReAct 适合「边探索边决定下一步」。但对于依赖关系明确、步骤之间可以并行或必须串行的复杂任务，纯 ReAct 有三个问题：

- 模型不一定在开始时形成完整任务边界，容易遗漏或重复步骤。
- 相互独立的步骤被串行执行，浪费可并行的机会。
- 用户无法在执行前审查整体方案，高风险动作缺少一次整体确认。

Plan-and-Execute 的意图就是把「制定计划」和「执行步骤」拆开：先构造一个显式 DAG，再按依赖关系推进。

### 2.2 DAG 带来的工程价值

DAG 是 Directed Acyclic Graph，即有向无环图。在任务编排中：

- 节点表示任务，边表示前置依赖。
- 无环约束保证存在可完成的拓扑顺序。
- 同一拓扑层（依赖都已满足）中的任务可以并行执行。

它把模型生成的自然语言计划，转换成**可验证、可调度、可解释**的结构。

### 2.3 本项目的职责拆分

| 组件 | 职责 |
|---|---|
| `Planner` | 让模型生成计划，清洗 JSON，规范化任务 ID 与依赖，简单目标快速路径 |
| `ExecutionPlan` | 维护任务图和拓扑/批次算法，不涉及 LLM |
| `PlanExecuteAgent` | 用户审阅、批次调度、并发控制、单任务执行 |
| `Task` | 单个节点的状态、结果与时间戳 |
| `AgentBudget` | 单任务内部循环的退出预算（Token / 停滞 / 显式轮数） |

这种拆分避免把 JSON 解析、图算法和 LLM 循环全部塞进一个类。需要强调的是：**一个 DAG 节点不等于一次模型调用**，节点内部仍是一个受 `AgentBudget` 约束的小型 ReAct 循环（见 §3.5）。

## 3. 总体架构与关键流程

### 3.1 总体架构

```mermaid
flowchart LR
    G[用户目标] --> S{isSimpleGoal?}
    S -- 是 --> MP[createMinimalPlan<br/>单节点计划]
    S -- 否 --> P[Planner.createPlan<br/>LLM 生成 JSON]
    P --> J[清洗 fence 并解析 JSON]
    J --> R[任务 ID 重命名为 task 序号<br/>映射依赖 ID]
    MP --> E[ExecutionPlan]
    R --> E
    E --> V[computeExecutionOrder<br/>DFS 拓扑排序 + 环检测]
    V --> Q[getExecutableTasksInOrder<br/>依赖已满足的任务]
    Q --> X[PlanExecuteAgent.executeTaskBatch]
    X --> W[单任务：主线程串行 + 实时输出]
    X --> T[多任务：固定线程池，最多 4 路并发]
    T --> O[按提交顺序读取 Future]
    O --> B[按任务顺序 flush 各任务缓冲区]
    W --> ST[主线程按批次顺序更新 Task 状态]
    B --> ST
    ST --> Q
    ST --> F[buildFinalResult / 汇总计划结果]
    RV[PlanReviewHandler] -.-> X
    C[CancellationContext] -.-> X
```

### 3.2 一次计划执行时序

```mermaid
sequenceDiagram
    participant User as 用户
    participant Planner as Planner
    participant Plan as ExecutionPlan
    participant Executor as PlanExecuteAgent
    participant Worker as Task Agent

    User->>Planner: createPlan(goal)
    Planner-->>Executor: ExecutionPlan(tasks, dependencies)
    Executor->>User: reviewHandler.review(goal, plan)
    User-->>Executor: EXECUTE / SUPPLEMENT / CANCEL
    Executor->>Plan: markStarted
    loop 每个执行批次
        Executor->>Executor: 检查取消（pre-batch）
        Executor->>Plan: getExecutableTasks + getExecutionOrder
        Plan-->>Executor: 依赖已满足的任务（按拓扑序）
        alt 只有一个任务
            Executor->>Worker: executeTask（主线程，实时输出）
        else 多个任务
            Executor->>Executor: fixedThreadPool(min(任务数, 4))，daemon 线程
            par 并行任务
                Executor->>Worker: execute task A（写入独立缓冲区）
                Executor->>Worker: execute task B（写入独立缓冲区）
            end
            Executor->>Executor: 按任务列表顺序读取 Future
            Executor->>Executor: 按任务顺序 flush 缓冲区
        end
        Executor->>Plan: 主线程逐条 markCompleted / markFailed
        Note over Executor: 失败且进度 < 50% → replan 并重入审阅<br/>（同批剩余结果被丢弃）
    end
    Executor-->>User: 计划汇总结果
```

### 3.3 核心领域模型

`ExecutionPlan`（`ExecutionPlan.java:8`）是 DAG 的聚合根，主要字段：

| 字段 | 含义 |
|---|---|
| `id` / `goal` | 计划唯一标识、用户原始目标 |
| `tasks` | `LinkedHashMap`，按 ID 保存任务并保持插入顺序 |
| `executionOrder` | DFS 拓扑排序后的稳定顺序 |
| `status` | 计划状态（`CREATED` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED`，枚举见 `ExecutionPlan.java:18-24`） |
| `summary` | Planner 生成的计划摘要（仅用于 `summarize()` 展示，见 §4） |
| `startTime` / `endTime` | 执行时间范围 |

`Task`（`Task.java:8`）表示一个可独立执行的工作单元：`id`、`description`、`type`、`dependencies`、`dependents`、`status`、`result`、`error`、`startTime`、`endTime`。`TaskType` 有 `PLANNING` / `FILE_READ` / `FILE_WRITE` / `COMMAND` / `ANALYSIS` / `VERIFICATION`（`Task.java:20-27`），它只影响节点 Prompt 的 `taskType` 变量，**不是 Java 执行分支**；其中 `PLANNING` 从未被 `Planner.parseTaskType` 产出（`Planner.java:165-174`）。

### 3.4 任务状态机现状

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> RUNNING: markStarted
    RUNNING --> COMPLETED: markCompleted(result)
    RUNNING --> FAILED: markFailed(error)
```

`TaskStatus` 的枚举值只有 `PENDING` / `RUNNING` / `COMPLETED` / `FAILED` / `SKIPPED`（`Task.java:29-35`），**没有 `CANCELLED`**。虽然定义了 `markSkipped()`（`Task.java:97-100`），但全仓库没有任何调用点，因此 `SKIPPED` 是不可达状态；`PlanStatus.CANCELLED`（`ExecutionPlan.java:23`）同样从未被赋值。取消不会落到任务或计划的状态上，而是直接让执行链提前返回一段取消文案（`PlanExecuteAgent.java:340-343`、`PlanExecuteAgent.java:558-562`、`PlanExecuteAgent.java:596-600`）。此外 `setStatus(...)` 在 `Task` 与 `ExecutionPlan` 上都有 public setter，但仓库内无调用点。

### 3.5 executePlan 完整调用链

#### 入口与审阅（`PlanExecuteAgent.java:251-329`）

1. `run(String, String)` 记录输入与策略（`PlanExecuteAgent.java:256-265`），先检查一次取消（`PlanExecuteAgent.java:267-271`）。
2. `runWithPlan()` 调用 `planner.createPlan(goal)`（`PlanExecuteAgent.java:299-302`）。
3. `reviewAndExecutePlan()` 循环读取 `PlanReviewHandler` 的决策（`PlanExecuteAgent.java:304-329`）：`CANCEL` 返回 `PlanRunOutcome.canceled`（不写回 assistant 历史，`PlanExecuteAgent.java:311-312`）；`SUPPLEMENT` 把补充要求拼进目标、**重建 Tool Policy** 后重新规划（`PlanExecuteAgent.java:315-327`）；空反馈按 `EXECUTE` 处理，避免审阅界面卡死（`PlanExecuteAgent.java:315-318`）。
4. `run()` 根据 `PlanRunOutcome.persistAssistantMessage()` 决定是否把结果写回历史（`PlanExecuteAgent.java:273-279`）。

#### 调度循环（`PlanExecuteAgent.java:331-409`）

1. `plan.markStarted()`（`PlanExecuteAgent.java:335`）。
2. 每轮先检查取消（`PlanExecuteAgent.java:340-343`），再用 `getExecutableTasksInOrder(plan)` 取「依赖已满足」且按 `executionOrder` 排好的任务（`PlanExecuteAgent.java:411-420`）。
3. 可执行集合为空则跳出循环（`PlanExecuteAgent.java:344-347`）。
4. `executeTaskBatch()` 执行整批（`PlanExecuteAgent.java:349-350`）。
5. 主线程**按批次结果顺序**逐个更新任务状态：成功 `markCompleted`（`PlanExecuteAgent.java:354-366`），失败 `markFailed`（`PlanExecuteAgent.java:370`）。
6. 失败时判断进度阈值（`PlanExecuteAgent.java:374`）：低于 50% 就走 `planner.replan` 并**递归返回**到审阅（`PlanExecuteAgent.java:376-377`）；否则把失败信息累积进 `finalResult`（`PlanExecuteAgent.java:380-383`）。
7. 循环结束后：既未全部完成又无失败 → 标记计划失败并返回「存在未满足依赖」（`PlanExecuteAgent.java:387-390`）；有失败 → 「计划部分完成，有任务失败」（`PlanExecuteAgent.java:396-402`）；否则 `markCompleted` 并返回汇总（`PlanExecuteAgent.java:404-408`）。

#### 单任务内部循环（`PlanExecuteAgent.java:519-655`）

1. 用 `PromptAssembler.assemble(PromptMode.PLAN, ...)` 组装节点 system prompt，只注入 `taskType`、`taskDescription`、项目记忆、外部上下文和 Skill 索引（`PlanExecuteAgent.java:523-530`）。
2. `buildTaskContext()` 拼出用户侧输入（`PlanExecuteAgent.java:536` → `1017-1052`），随后追加长期记忆检索结果与 Skill 正文（`PlanExecuteAgent.java:533-540`）。
3. 创建 `AgentBudget.fromLlmClient(llmClient)`（`PlanExecuteAgent.java:555`），进入 `while (true)`（`PlanExecuteAgent.java:557`）。每轮先检查取消（`PlanExecuteAgent.java:558-562`），再 `budget.check()` 判断是否命中兜底（`PlanExecuteAgent.java:564-576`），然后注入待处理的 LSP 诊断、按需压缩历史（`PlanExecuteAgent.java:580-581`），最后调 `llmClient.chat`（`PlanExecuteAgent.java:587-591`）。
4. 无 Tool Call 时任务收尾返回（`PlanExecuteAgent.java:611-628`）；有 Tool Call 时记录预算与签名（`PlanExecuteAgent.java:631`）、保存 assistant `tool_calls` 消息（`PlanExecuteAgent.java:633-637`）、`resetBetweenIterations()` 收尾流式区（`PlanExecuteAgent.java:641`）、执行工具并把结果回灌进 `messages`（`PlanExecuteAgent.java:643-652`）。
5. 命中预算后**不抛异常、也不算失败**，而是调用 `finalizePartialTask`：追加一次「不要再调用任何工具」的收尾指令（`PlanExecuteAgent.java:668-676`），做一次 tools 为空的收尾调用（`PlanExecuteAgent.java:681`），把结果加上「⚠️ 部分完成」前缀返回（`PlanExecuteAgent.java:697-703`、`PlanExecuteAgent.java:706-709`）。调度层把它当成功 `markCompleted`（见 §4）。

### 3.6 主调度伪代码

```text
executePlan(plan):
    plan.markStarted()
    while true:
        if cancelled: return 取消文案          # 不动任何状态
        executable = plan 中依赖已满足的任务，按 executionOrder 排序
        if executable 为空: break
        results = executeTaskBatch(executable)
        for r in results:                       # 主线程顺序更新
            if r 成功: r.task.markCompleted(r.result)
            else:
                r.task.markFailed(r.error)
                if plan.getProgress() < 50%:     # 阈值判定在批次内
                    return reviewAndExecutePlan(planner.replan(plan, r.error))
                累积失败信息到 finalResult       # 同批剩余结果继续处理
    if 未全部完成 且 无失败: plan.markFailed(); return "存在未满足依赖"
    return 汇总（有失败 → 部分完成；否则 markCompleted）
```

### 3.7 拓扑排序与批次计算

`computeExecutionOrder()`（`ExecutionPlan.java:94-108`）对每个未访问节点做 DFS，`topologicalSort()`（`ExecutionPlan.java:110-135`）维护 `visited` 与 `visiting` 两个集合：节点重新出现在 `visiting` 中即命中回边，返回 `false` 表示有环。`Planner.parsePlan` 在解析阶段直接调用它，有环就抛 `IOException("计划中存在循环依赖")`（`Planner.java:155-157`），因此环永远进不了调度阶段。

```text
computeExecutionOrder:
    order = [], visited = {}, visiting = {}
    for task in tasks:
        if task not in visited and not dfs(task): return false
    return true

dfs(task):
    if task in visiting: return false      # 回边 → 有环
    if task in visited: return true
    visiting.add(task)
    for dep in task.dependencies:
        if dep exists and not dfs(dep): return false
    visiting.remove(task); visited.add(task); order.add(task)
    return true
```

批次有两个不同用途的入口，**不要混用**：

- `getExecutionBatches()`（`ExecutionPlan.java:266-292`）：只看图结构，按「依赖是否位于已完成集合」分层，批次为空即 `break`。仅用于计划预览（`summarize()`，`ExecutionPlan.java:243-264`）。
- `getExecutableTasks()`（`ExecutionPlan.java:85-89`）：看**运行时状态**，逐任务走 `Task.isExecutable()`（`Task.java:114-123`）。这是真正驱动调度的集合。

### 3.8 典型场景推演

用户目标：

> 修改用户模块和订单模块的日志格式，最后运行回归测试。

Planner 可能生成：

```json
{
  "tasks": [
    {"id": "t1", "description": "检查用户模块日志实现", "dependencies": []},
    {"id": "t2", "description": "检查订单模块日志实现", "dependencies": []},
    {"id": "t3", "description": "修改用户模块日志", "dependencies": ["t1"]},
    {"id": "t4", "description": "修改订单模块日志", "dependencies": ["t2"]},
    {"id": "t5", "description": "运行回归测试", "dependencies": ["t3", "t4"]}
  ]
}
```

解析后任务 ID 会被规范化为 `task_<序号>`（`Planner.java:124`），依赖 ID 同步重映射（`Planner.java:144`）。理论批次为 `{t1,t2}` → `{t3,t4}` → `{t5}`（`getExecutionBatches`，`ExecutionPlan.java:266`）。前两批各有两个互不依赖的任务，会走并行路径；`t5` 必须等两个修改任务都 `COMPLETED` 后才进入可执行集合。并行执行时每个任务写自己的 `ByteArrayOutputStream`，主线程在批次末尾按任务顺序 flush（`PlanExecuteAgent.java:485-492`），因此即使 `t2` 先完成，终端输出仍按计划顺序呈现。

## 4. 设计意图 vs 实际实现

以下是文档意图与代码实际行为存在差异的地方。每条给出源码位置，具体数值以源码为准。

| 主题 | 设计意图 | 实际实现 | 源码位置 |
|---|---|---|---|
| 任务取消状态 | 以为取消会让任务进入「已取消」状态 | **不存在**。`TaskStatus` 没有 `CANCELLED`，取消只是让循环提前 `return` 取消文案，任务状态保持不变 | `Task.java:29-35`、`PlanExecuteAgent.java:340-343` |
| 任务跳过状态 | 以为前置任务失败会让后继任务 `SKIPPED` | `SKIPPED` 枚举值存在、`markSkipped()` 也有实现，但**全仓库无任何调用点**，是不可达状态；前置失败只会让后继任务停留在 `PENDING` | `Task.java:97-100`、`Task.java:114-123` |
| 计划取消状态 | 以为 `PlanStatus.CANCELLED` 会被赋值 | 枚举值声明了，但**从未被赋值**；取消路径不碰 `plan.status` | `ExecutionPlan.java:23`、`PlanExecuteAgent.java:311-312` |
| mid-task 取消语义 | 以为取消会让任务失败或停下 | **当作成功**。`executeTask` 返回取消字符串（如 `⏹️ 已取消任务 [id]。`），`TaskExecutionResult.success` 不带 error，主线程随即 `markCompleted`——任务被标记为「已完成」 | `PlanExecuteAgent.java:558-562`、`PlanExecuteAgent.java:596-600`、`PlanExecuteAgent.java:354-355` |
| pre-batch 取消语义 | 以为会置计划状态 | 只有这一步**不动状态**：直接返回文案，计划停留在 `RUNNING` | `PlanExecuteAgent.java:340-343` |
| 节点 Prompt 内容 | 文档称节点 Prompt 包含「当前计划摘要」 | **不含**。`buildTaskContext` 只拼「总目标 + 当前任务 + 依赖任务结果 + 授权 URL」；`PromptMode.PLAN` 也只注入 taskType/taskDescription，计划 `summary` 仅用于 `summarize()` 展示 | `PlanExecuteAgent.java:1017-1052`、`PlanExecuteAgent.java:523-530` |
| 缺失依赖 | 文档称依赖不存在会让任务「无法推进」 | **静默丢弃**。解析期 `idMapping` 查不到就保留原 ID，`getTask` 为 null 时直接跳过，依赖不入边；`buildTaskContext` 同样 `continue` 跳过 null 依赖。结果是该任务**没有任何依赖，立刻可执行** | `Planner.java:144-149`、`PlanExecuteAgent.java:1029-1031` |
| 节点预算耗尽 | 文档称耗尽后返回节点失败 | **返回成功**。`finalizePartialTask` 把收尾调用结果加「⚠️ 部分完成（…）」前缀作为**成功结果**返回（`TaskRunResult` 无 error），调度层随即 `markCompleted` | `PlanExecuteAgent.java:658-709`、`PlanExecuteAgent.java:354-355` |
| 预算机制本身 | 旧文档的「迭代上限 `MAX_TASK_ITERATIONS`」 | **该常量已不存在**，替换为 `AgentBudget`：Token 预算 / 停滞检测 / 可选硬轮数，默认均不设硬限，只有显式系统属性才启用 | `AgentBudget.java:35-46`、`AgentBudget.java:78-88`、`AgentBudget.java:127-138` |
| replan 输入 | 文档列出「失败任务」作为 replan 输入 | `Planner.replan` 只传三样：原目标、`failureReason` 字符串、`COMPLETED` 任务列表；**不传失败任务清单** | `Planner.java:186-205` |
| 50% 阈值判定位置 | 以为在整批完成后再判定 | 判定在**逐条处理批次结果的循环内部**（`for (TaskExecutionResult ...)`），因此首个失败任务就可能触发 replan，此时同批兄弟任务尚未 `markCompleted`，进度被低估，剩余结果直接丢弃 | `PlanExecuteAgent.java:351`、`PlanExecuteAgent.java:374-377` |
| replan 后取消的持久化 | 以为取消不写历史 | 嵌套 `reviewAndExecutePlan(...).result()` 只取字符串，取消文案被外层 `PlanRunOutcome.executed` 重新包裹，`persistAssistantMessage` 变为 `true` | `PlanExecuteAgent.java:377`、`PlanExecuteAgent.java:308` |
| 最终结果汇总 | 以为会收集所有任务结果 | `buildFinalResult` 只取**叶子任务**（`getDependents().isEmpty()`），且**跳过有过流式输出的任务**；叶子为空时回退到「最后一个非流式、非空」的任务结果 | `PlanExecuteAgent.java:1054-1083` |
| 零任务计划 | 以为空 `tasks` 会报错 | 合法 JSON 但 `tasks` 缺失/为空 → **0 任务计划**：`computeExecutionOrder` 对空集返回 true，`isAllCompleted()` 对空集 vacuously true，最终报告「✅ 计划执行完成！」 | `Planner.java:113-116`、`ExecutionPlan.java:94-108`、`ExecutionPlan.java:161-164`、`PlanExecuteAgent.java:404-408` |
| 单任务 vs 多任务输出 | 以为所有任务都走缓冲 | 只有单任务批次把真实 `out` 传给节点（**实时流式输出**）；多任务批次才创建每任务独立缓冲，主线程按顺序 flush | `PlanExecuteAgent.java:425-433`、`PlanExecuteAgent.java:451-492` |
| 线程池生命周期 | 少见显式说明 | 线程为 **daemon**，命名 `codeagent-plan-executor`；批次结束在 `finally` 中调用 `shutdownNow()` | `PlanExecuteAgent.java:445-449`、`PlanExecuteAgent.java:495-497` |
| 简单目标快速路径 | 以为一定调用模型规划 | `isSimpleGoal` 先排多步骤提示词（「然后/并且/并/再/最后/同时/先/之后/接着/以及」），再卡长度阈值，最后要求命中动作词；命中则走 `createMinimalPlan` 单节点计划，不调 LLM | `Planner.java:207-244`、`Planner.java:246-254` |
| 简单目标类型推断 | 以为按顺序判断 | `inferSimpleTaskType` 里 `「查看」&&「文件」` 与 `||` 混用，运算符优先级使「查看」单独出现也会命中 FILE_READ 分支 | `Planner.java:264-280` |
| 任务 ID | 以为沿用模型给的 ID | 解析后统一重命名为 `task_<序号>`，并建立 `idMapping` 供依赖重映射 | `Planner.java:122-132`、`Planner.java:144` |
| 「无法推进」分支 | 以为这是常见失败路径 | `if (!plan.isAllCompleted() && !plan.hasFailed())` 需要存在「既未完成也未失败」的前置任务；环在解析期已被拒绝、`SKIPPED` 从未设置，因此该分支在 Planner 正常产出的计划上基本不可达 | `PlanExecuteAgent.java:387-390` |
| 未知任务类型 | 以为会报错 | `parseTaskType` 的 `default` 静默回退为 `ANALYSIS` | `Planner.java:165-174` |
| 死代码 | — | `PlanRunOutcome.failed(...)` 定义了但无调用点；`TaskType.PLANNING` 从未被产出 | `PlanExecuteAgent.java:56-58`、`Task.java:21` |

## 5. 设计取舍

### 5.1 DFS 拓扑排序 vs Kahn 算法

当前用 DFS + 递归栈（`ExecutionPlan.java:110-135`）。优点是实现紧凑、可直接用 `visiting` 集合检测环、适合小规模任务图。Kahn 算法更容易直接产出分层批次和定位入度异常，代价是要额外维护入度表。当前计划规模小，DFS 够用。

代价：批次结构靠 `getExecutionBatches` 单独算一遍，与拓扑顺序是两套代码，容易出现「理论批次」和「运行时批次」不一致的理解成本。

### 5.2 显式线程池 vs CompletableFuture

当前显式使用 `ExecutorService` + `Future`（`PlanExecuteAgent.java:445-483`）。优点：并发度一眼可见、提交顺序与结果顺序天然对齐、和同步的 `ToolRegistry` API 配合简单。CompletableFuture 适合更复杂的异步组合，但会引入更深的异常链和上下文传播复杂度。

代价：并发上限写死在调度器里（`PlanExecuteAgent.java:445`），没有按任务权重或资源压力动态调节。

### 5.3 DAG 节点：一次 Tool Call vs 一个小型 ReAct 循环

当前每个节点是一个可多轮推理的 Agent 任务，能自行探索和纠错。代价是节点耗时与副作用不完全可预测，也无法给单节点做精确时间预算。`AgentBudget` 默认不设硬限，正是把「是否继续下一轮」的主导权交给模型自己（`AgentBudget.java:11-34`）。

### 5.4 独立缓冲区 vs 直接共享 stdout

并行任务同时产生 reasoning、content 和工具日志，共享 `PrintStream` 会让字符级输出交错，transcript 无法对应计划。因此每个任务写自己的 `ByteArrayOutputStream`，批次末尾按任务顺序整体 flush（`PlanExecuteAgent.java:451-492`）。代价是**牺牲了并行任务的逐字符实时显示**——用户看到的是延迟到批次末尾的整块输出。

### 5.5 未知依赖：静默丢弃 vs 解析期拒绝

`parsePlan` 发现依赖 ID 不存在时选择静默跳过（`Planner.java:144-149`），好处是模型偶发笔误不会让整个计划报废。代价是**语义被悄悄改写**：本应等待依赖的任务变成了无依赖任务，可能提前执行并产生错误结果。更严格的做法是在解析期直接拒绝并让模型重试。

### 5.6 预算耗尽：收尾 vs 判失败

预算命中后选择「禁用工具 + 一次最佳努力收尾」（`AgentBudget.java:186-195`、`PlanExecuteAgent.java:658-709`），而不是丢弃已完成工作，这符合 AGENTS.md 的约定。代价是收尾结果被当作**成功**回传给调度层（`TaskRunResult` 无 error），最终汇总里只会看到「⚠️ 部分完成」文案，任务状态却是 `COMPLETED`。

### 5.7 计划先审阅 vs 直接执行

计划可能包含写文件、执行命令或外部服务调用。先审阅让用户看到整体方案后补充约束或取消，与工具级 HITL 互补：计划审阅确认方向，工具审批确认具体高风险动作。代价是多一次交互往返，且重新规划会重新进入审阅（`PlanExecuteAgent.java:304-329`）——但 TUI 的 `/plan` 固定传入「总是执行」的 handler，审阅被跳过（`TuiSessionController.java:269`）。

## 6. 失败与边界矩阵

| 失败点 | 检测方式 | 当前处理 | 影响范围 | 源码位置 |
|---|---|---|---|---|
| Planner LLM 异常 | 捕获 `IOException` | 由 `run` 包成「执行失败」并写入历史 | 整个计划 | `PlanExecuteAgent.java:284-292` |
| Planner 返回非法 JSON | Jackson 解析异常 | 抛出，计划不进入执行 | 整个计划 | `Planner.java:111` |
| 计划 JSON fence | `replaceAll` 清洗 | 去除 ```` ```json ```` 与 ```` ``` ```` | — | `Planner.java:107-109` |
| 合法 JSON 但 `tasks` 缺失/为空 | 无检测 | **0 任务计划**，直接报告「计划执行完成」 | 整个计划（静默成功） | `Planner.java:113-116`、`PlanExecuteAgent.java:344-347`、`PlanExecuteAgent.java:404-408` |
| 环依赖 | DFS `visiting` 命中 | 抛 `IOException("计划中存在循环依赖")` | 整个计划 | `Planner.java:155-157`、`ExecutionPlan.java:113-115` |
| 未知任务类型 | `parseTaskType` default | 回退为 `ANALYSIS` | 单任务 | `Planner.java:165-174` |
| 未知依赖 ID | `idMapping` / `getTask` 未命中 | **静默丢弃该依赖**，任务变为立即就绪 | 单任务（语义被改写） | `Planner.java:144-149`、`PlanExecuteAgent.java:1029-1031` |
| 用户取消审阅 | `PlanReviewAction.CANCEL` | 返回取消文案，不写 assistant 历史 | 整个计划 | `PlanExecuteAgent.java:311-312` |
| 执行中取消（pre-batch） | `CancellationContext` | 返回取消文案，**不改任何状态** | 剩余任务 | `PlanExecuteAgent.java:340-343` |
| 执行中取消（mid-task） | `CancellationContext` | 返回「已取消任务」字符串，**被当作成功**并 `markCompleted` | 单任务（状态被误置） | `PlanExecuteAgent.java:558-562`、`PlanExecuteAgent.java:596-600`、`PlanExecuteAgent.java:354-355` |
| 并行线程抛异常 | `ExecutionException` | 解包 cause，包装为任务失败 | 单任务 | `PlanExecuteAgent.java:476-482` |
| 等待被中断 | `InterruptedException` | 恢复中断位，包装为任务失败 | 单任务 | `PlanExecuteAgent.java:473-475` |
| 节点预算耗尽 | `AgentBudget.check()` | **不报错**，一次无工具收尾，返回「⚠️ 部分完成」并被标记完成 | 单任务 | `PlanExecuteAgent.java:564-576`、`PlanExecuteAgent.java:658-709` |
| 工具失败 | Tool result 语义 | 作为 tool 消息回灌，交给节点模型纠错 | 单工具/节点 | `PlanExecuteAgent.java:643-652` |
| 早期任务失败 | 进度低于 50% | 带 `failureReason` 调 `replan`，回到审阅，**同批剩余结果丢弃** | 后续计划 | `PlanExecuteAgent.java:374-377` |
| 后期任务失败 | 进度不低于 50% | 累积失败信息，保留已完成结果，返回「部分完成」 | 失败节点及其依赖 | `PlanExecuteAgent.java:380-383`、`PlanExecuteAgent.java:396-402` |
| 依赖失败 | 前置任务为 `FAILED` | 后继任务永远不满足 `isExecutable`，停留在 `PENDING`，循环因无可执行任务而退出 | 剩余节点 | `Task.java:114-123`、`PlanExecuteAgent.java:344-347` |
| 无可执行任务且无失败 | `getExecutableTasks` 返回空 | 标记计划失败并返回「存在未满足依赖」（正常计划上基本不可达） | 剩余节点 | `PlanExecuteAgent.java:387-390` |

## 7. 测试策略与证据

以下只列出仓库中**确实存在**的测试与其断言点，避免把设计意图当成已覆盖。

### 7.1 ExecutionPlanTest（`src/test/java/com/codeagent/plan/ExecutionPlanTest.java`）

| 用例 | 覆盖点 | 位置 |
|---|---|---|
| `computeExecutionOrderRespectsDependencies` | 链式依赖的拓扑顺序 | `ExecutionPlanTest.java:13` |
| `executableTasksWaitUntilDependenciesComplete` | 依赖未完成时不可执行、完成后进入可执行集合 | `ExecutionPlanTest.java:27` |
| `addDependencyMutatesTaskState` | `addDependency` 写入依赖列表 | `ExecutionPlanTest.java:43` |
| `addTaskBuildsDependentRelationship` | `addTask` 自动登记反向 `dependents` | `ExecutionPlanTest.java:52` |
| `executableTasksCanExposeParallelBatch` | 多根节点的可执行集合与收敛 | `ExecutionPlanTest.java:64` |
| `summarizeKeepsPlanPreviewCompact` | 摘要紧凑、含批次与可执行数、不输出完整 DAG | `ExecutionPlanTest.java:84` |
| `executionBatchesFollowDagLayers` | 批次按 DAG 分层 | `ExecutionPlanTest.java:104` |

### 7.2 PlannerTest（`src/test/java/com/codeagent/plan/PlannerTest.java`）

| 用例 | 覆盖点 | 位置 |
|---|---|---|
| `createsMinimalPlanForSimpleGoalWithoutCallingLlm` | 简单目标走快速路径，单节点、类型推断正确，且**断言 LLM 未被调用** | `PlannerTest.java:16` |
| `delegatesComplexGoalToLlmPlannerPath` | 复杂目标走 LLM；JSON 解析、依赖映射（原 ID → 规范化 ID）、项目记忆注入 system prompt | `PlannerTest.java:29` |

### 7.3 PlanExecuteAgentTest（`src/test/java/com/codeagent/agent/PlanExecuteAgentTest.java`）

| 用例 | 覆盖点 | 位置 |
|---|---|---|
| `shouldKeepPlanExecutionArtifactsInTheTaskConversationOnly` | 节点内 Tool Call → 回灌 → 最终 content 的多轮路径；结果只写短期记忆、不写长期记忆 | `PlanExecuteAgentTest.java:38` |
| `shouldContinuePlanTaskBeyondLegacyFiveIterationLimit` | 清除硬轮数属性后节点可超过旧「5 轮」上限，断言工具调用次数 | `PlanExecuteAgentTest.java:88` |
| `shouldNotExtractFactsWhenPlanIsCanceled` | 审阅取消返回固定文案且不提取事实 | `PlanExecuteAgentTest.java:134` |
| `shouldNotRepeatStreamedTaskOutputInFinalPlanSummary` | 任务正文已流式输出后，最终汇总不重复正文 | `PlanExecuteAgentTest.java:158` |
| `shouldNotPrintEmptyTaskReasoningHeadingAndShouldUseOutputLabel` | 纯空白 reasoning 不打印「任务思考」标题；流式正文标注为「任务输出」而非「任务结果」 | `PlanExecuteAgentTest.java:183` |
| `supplementRebuildsToolPolicyBeforeReplanning` | 补充要求含联网意图时，replan 后重建 Tool Policy、放行 `web_search` | `PlanExecuteAgentTest.java:227` |
| `noWebSupplementTightensToolPolicyBeforeReplanning` | 补充要求明确「不需要联网」时收紧策略，工具不被调用 | `PlanExecuteAgentTest.java:261` |
| `dependentTaskInheritsOnlyTypedSearchUrlProvenance` | 依赖任务继承前置 `web_search` 的**类型化 URL 授权**，下游 `web_fetch` 可执行 | `PlanExecuteAgentTest.java:289` |

### 7.4 尚未覆盖（诚实清单）

仓库中**没有**针对以下行为的测试，它们是当前实现的可信度缺口：

- 环依赖检测与 `parsePlan` 抛错路径（`ExecutionPlanTest` 未包含环用例）。
- 0 任务计划被报告为「执行完成」的边界。
- `replan` 触发与 50% 阈值分支。
- `PlanExecuteAgent` 的多任务并行路径、并发峰值与结果顺序。
- `buildFinalResult` 的叶子筛选与流式跳过逻辑。
- 未知依赖被静默丢弃的语义。
- 预算耗尽时 `finalizePartialTask` 的「部分完成」前缀与成功状态。
- mid-task 取消被 `markCompleted` 的语义。
- 线程池中断、超时与 `shutdownNow()` 后的状态一致性。

### 7.5 手工验收关注点

- 多任务批次中，不同任务的输出块是否按计划顺序整体出现、无字符交错。
- 单任务批次是否实时流式输出（与多批次的延迟整块输出形成对比）。
- 计划审阅的「补充要求」路径是否重新规划并再次进入审阅。
- 任务失败后，依赖它的任务是否确实不再执行、并在汇总中体现。

## 8. 面试讲解模板

### 8.1 30 秒版本

我在 ReAct 之外实现了一套 Plan-and-Execute 模式。Planner 用模型把复杂目标解析成带依赖的 Task DAG，并把模型给的 ID 规范化；ExecutionPlan 用 DFS 做拓扑排序和环检测，按依赖是否满足计算可执行集合；PlanExecuteAgent 把同一批次中互不依赖的任务放进固定线程池，最多 4 路并行，每个任务写独立输出缓冲、主线程按计划顺序回放，保证并行执行和稳定展示顺序。任务失败时按 50% 进度阈值决定重新规划还是保留部分结果，执行前还支持用户审阅和补充。

### 8.2 2 分钟版本

这套编排把「计划生成」「图算法」「调度执行」三层拆开。Planner 负责让模型输出结构化 JSON，做 fence 清洗、任务 ID 归一化和依赖映射，并对简单目标走不调用模型的快速路径；ExecutionPlan 是纯数据结构，负责拓扑排序、环检测、批次计算和进度；PlanExecuteAgent 只关心怎么把图跑起来。

调度循环每轮取「依赖全部完成」的任务，按拓扑顺序排好。单个任务走主线程串行并实时流式输出；多个任务才建固定线程池，上限 4 路并行，每个节点在独立缓冲区里写日志，主线程按提交顺序读 Future、再按任务顺序 flush，所以并发不会打乱 transcript。节点本身不是一次调用，而是受 `AgentBudget` 约束的小循环，内部还能多轮 Tool Call、注入 LSP 诊断和压缩历史；预算默认不设硬限，命中才做一次无工具收尾。

任务状态只由主线程按顺序更新，避免多线程争抢聚合状态。失败处理上，早期失败（进度低于 50%）会带失败原因重新规划并重新进入用户审阅；后期失败保留已完成结果并明确报告「部分完成」。需要诚实说明三点：预算耗尽的节点会以「部分完成」文案被标记为完成而不是失败；mid-task 取消的返回值也会被当成功 `markCompleted`；缺失依赖会被静默丢弃而不是让任务卡住——这些都是我对照源码才发现实现与意图的差距。

## 9. 高频面试问答

### Q1：为什么使用 DAG，而不是让模型直接一步步执行？

DAG 能显式表达前置依赖，同时识别可并行任务；无环约束保证存在合法拓扑顺序。它把自然语言计划转换成可验证、可调度的结构，也让用户在执行前就能审查整体方案。

### Q2：如何检测循环依赖？

DFS 中维护 `visiting` 递归栈。再次访问 `visiting` 中的节点表示存在回边（`ExecutionPlan.java:113-115`）。`Planner.parsePlan` 在解析阶段就调用 `computeExecutionOrder()`，有环直接抛 `IOException`（`Planner.java:155-157`）。

### Q3：如何计算并行批次？

静态批次由 `getExecutionBatches()` 按「依赖是否位于已完成集合」逐层选出（`ExecutionPlan.java:266-292`），只用于 `summarize()` 预览；运行时真正驱动调度的是 `getExecutableTasks()` + `Task.isExecutable()`（`ExecutionPlan.java:85-89`、`Task.java:114-123`），再由 `getExecutableTasksInOrder` 按 `executionOrder` 重排（`PlanExecuteAgent.java:411-420`）。批次为空即退出，避免死循环。

### Q4：为什么并发上限是 4？

这是进程内资源保护值，用于限制并发的模型与工具请求。它硬编码在调度器里，见 `PlanExecuteAgent.java:445`。选择固定上限是为了行为简单可预测，代价是不能按任务权重动态调节。

### Q5：并行结果如何保持顺序？

两层保证：输入按 `executionOrder` 排序后提交，`Future` 按提交顺序读取（`PlanExecuteAgent.java:470-483`）；每个任务的输出写独立 `ByteArrayOutputStream`，批次末尾按任务顺序 flush（`PlanExecuteAgent.java:485-492`）。

### Q6：任务失败后为什么不是全部终止？

已完成任务可能仍有价值。代码按失败时的进度决定：低于 50% 触发 `replan`，否则保留部分结果（`PlanExecuteAgent.java:374-383`）。但要注意判定发生在逐条处理批次结果的循环里，因此首个失败任务就可能触发，此时同批其他任务还没标记完成，剩余结果会被直接丢弃。

### Q7：重新规划会绕过用户确认吗？

CLI 不会。`replan` 之后会重新进入 `reviewAndExecutePlan()`（`PlanExecuteAgent.java:376-377`），继续经过 `PlanReviewHandler`。但 TUI 的 `/plan` 传入的是固定 EXECUTE handler（`TuiSessionController.java:269`），本就无交互审阅。

### Q8：DAG 节点内部如何执行？

每个节点有自己独立的消息列表，内部是一个 `while (true)` 循环（`PlanExecuteAgent.java:557`），退出由 `AgentBudget` 三条件先到先触发：Token 预算、连续相同工具调用的停滞检测、显式硬轮数（`AgentBudget.java:127-138`），三者默认都不设硬限。循环内可多轮 Tool Call、注入 LSP 诊断、按需压缩历史，也能流式输出。

### Q9：如何处理无效依赖？

需要区分两件事。实际上**依赖 ID 不存在时会被静默丢弃**（`Planner.java:144-149`），任务会变成无依赖、立刻可执行——这是实现与意图的差距。而「依赖存在但前置任务失败」时，后继任务会永久停留在 `PENDING`，循环因无可执行任务而退出（`Task.java:114-123`）。更严格的做法是在解析期校验全部依赖 ID。

### Q10：任务状态由谁更新？

并行线程只返回 `TaskExecutionResult`（`PlanExecuteAgent.java:68-82`）；主调度线程按批次结果顺序更新 `Task` 的状态（`PlanExecuteAgent.java:351-384`）。这样可以减少多线程直接修改计划聚合状态的竞争。

### Q11：计划审阅和 HITL 有何区别？

计划审阅针对整体执行方案，HITL 针对具体危险 Tool Call。前者确认方向，后者确认具体高风险动作。

### Q12：如何处理用户补充要求？

`SUPPLEMENT` 会把补充内容拼进原目标、**重建 Tool Policy** 后重新调用 Planner（`PlanExecuteAgent.java:315-327`），不会在原图上做不透明的局部修改。空反馈按执行处理，避免审阅界面因无内容陷入循环（`PlanExecuteAgent.java:315-318`）。

### Q13：为什么设置节点预算而非固定迭代上限？

旧实现有固定迭代上限，现已替换为 `AgentBudget`：默认不限制轮数（`AgentBudget.java:45-46`、`AgentBudget.java:78-88`），只在显式系统属性或停滞检测命中时才收尾。**需要特别注意**：预算耗尽后节点不会失败，而是返回带「⚠️ 部分完成」前缀的结果并被 `markCompleted`（`PlanExecuteAgent.java:658-709`），因此这条兜底实际会产出「任务状态为成功的部分完成」。

### Q14：并行任务修改同一文件怎么办？

当前依赖 Planner 正确建立冲突任务间的依赖。DAG 只表达显式依赖，如果两个任务写同一文件而 Planner 没有建边，可能产生逻辑冲突。进一步增强可以引入资源声明、路径写锁或静态写集分析。

### Q15：为什么失败阈值用 50%？

它是简单的成本启发式，不是业务保证：早期失败时推倒重来更划算，后期失败时保留已完成工作。当前实现把判定放在批次结果循环内部（`PlanExecuteAgent.java:374`），所以它对「同批其他任务」的完成情况感知偏保守，进度是被低估的。

### Q16：如何保证任务结果能传给下游？

`buildTaskContext` 从**依赖任务**中提取 ID、描述、状态与结果，加入下游节点 Prompt（`PlanExecuteAgent.java:1017-1052`）；依赖的 `web_search` 授权 URL 也会以类型化形式注入（`PlanExecuteAgent.java:1042-1048`）。非依赖节点的结果不会被无条件注入。注意节点 Prompt 里**没有**「计划摘要」，只有总目标、当前任务和依赖结果。

### Q17：DAG 能持久化吗？

不能。Plan 的执行状态主要在内存（`ExecutionPlan` 里只有 `status` 和时间戳）。`Runtime API` 与后台 `DurableTask` 是另一套机制，它们走 `runHeadlessTask` → 普通 `Agent`，**完全绕过 DAG**（`Main.java:1004-1015`、`Main.java:969`、`Main.java:1019`）。

### Q18：如何测试并发确实发生？

可以用受控 Latch 或 Barrier 的假 `LlmClient`，让两个节点同时进入执行点，并断言最大并发数与最终顺序，不能只比较总耗时。目前 `PlanExecuteAgent` 的并行路径**还没有这样的测试**，这是已知缺口。

### Q19：最终汇总如何生成？

`buildFinalResult` 只收集叶子任务（`getDependents().isEmpty()`）且跳过已流式输出的任务；如果叶子为空或全部被跳过，回退到「最后一个非流式、非空」的任务结果（`PlanExecuteAgent.java:1054-1083`）。这意味着中间节点的结果不会出现在最终汇总里。

### Q20：下一步如何演进？

可以增加图持久化与断点恢复、资源锁与写集分析、任务权重与关键路径估算、节点级重试与超时预算、在解析期严格校验依赖 ID、修正「预算耗尽/取消返回成功」与「缺失依赖静默丢弃」语义问题，以及补上并行路径与 replan 分支的测试。

## 10. 简历条陈与源码证据

| 简历原句 | 代码证据 |
|---|---|
| DAG任务编排：构建 Plan-and-Execute 任务编排能力 | `Planner.createPlan` — `Planner.java:59`；调度入口 `PlanExecuteAgent.executePlan` — `PlanExecuteAgent.java:331`；职责拆分见 `ExecutionPlan.java:8` |
| 将复杂目标拆解为带依赖关系的 DAG 任务 | 解析并建立依赖/被依赖关系 — `Planner.java:122-152`；`Task.dependencies` / `dependents` — `Task.java:15-16`；`ExecutionPlan.addTask` 自动登记反向边 — `ExecutionPlan.java:48-57` |
| 通过拓扑排序 | `computeExecutionOrder()` — `ExecutionPlan.java:94`；DFS 与环检测 `topologicalSort()` — `ExecutionPlan.java:110-135` |
| 和执行批次调度任务 | 静态批次 `getExecutionBatches()` — `ExecutionPlan.java:266`；运行时可执行集合 `getExecutableTasks()` — `ExecutionPlan.java:85`；批次执行 `executeTaskBatch()` — `PlanExecuteAgent.java:422`；带顺序的可执行集合 `getExecutableTasksInOrder` — `PlanExecuteAgent.java:411` |
| 无依赖任务支持最多 4 路并行执行 | `Executors.newFixedThreadPool(Math.min(size, 4), ...)` — `PlanExecuteAgent.java:445`；单任务走串行内联路径 — `PlanExecuteAgent.java:425-437` |
| 并保留工具结果的原始顺序 | Future 按提交顺序读取 — `PlanExecuteAgent.java:470-483`；各任务独立 `ByteArrayOutputStream` 并按任务顺序 flush — `PlanExecuteAgent.java:451-492`；工具结果按原序回灌 — `PlanExecuteAgent.java:645-652` |
| （隐含）计划审阅与补充 | `PlanReviewHandler` / `PlanReviewDecision` — `PlanExecuteAgent.java:84-106`、`PlanExecuteAgent.java:304-329`；CLI 交互 — `Main.java:1440-1495` |
| （隐含）失败重新规划 | `Planner.replan` 与 50% 阈值分支 — `Planner.java:186-205`、`PlanExecuteAgent.java:374-377` |
| （隐含）节点内多轮工具调用 | `executeTask` 的 `while(true)` 循环 — `PlanExecuteAgent.java:519-655`；预算兜底收尾 — `PlanExecuteAgent.java:658-709` |

## 11. 当前实现边界

- DAG 执行状态主要在内存，没有计划级断点恢复或持久化；Runtime API 与 DurableTask 走的是绕过 DAG 的另一条路径。
- 并发是单进程线程池，不是分布式工作流引擎；并发上限硬编码在 `PlanExecuteAgent.java:445`。
- **`TaskStatus.SKIPPED` 是不可达状态**（`markSkipped()` 定义了但无调用点），`PlanStatus.CANCELLED` 从未被赋值，`Task`/`ExecutionPlan` 的 `setStatus(...)` 无外部调用点，取消不落到状态机上。
- **mid-task 取消被当作成功**：取消字符串经 `TaskRunOutcome`/`TaskRunResult` 无 error 返回，任务被 `markCompleted`；只有 pre-batch 取消不动状态。
- **预算耗尽返回「部分完成」但任务状态是完成**：`finalizePartialTask` 的 `TaskRunResult` 无 error，调度器照常 `markCompleted`。
- **缺失依赖被静默丢弃**：未知依赖 ID 不会让任务卡住，而是让它变成无依赖任务立刻执行，属于被悄悄改写的语义。
- **50% 阈值判定在批次结果循环内部**，可能在同批兄弟任务标记前触发，导致进度被低估且剩余结果被丢弃；replan 后的取消语义也在嵌套调用中丢失（`persistAssistantMessage` 被外层重置为 `true`）。
- **合法 JSON 但 `tasks` 缺失/为空会被报告为「计划执行完成」**，属于静默成功边界。
- `buildFinalResult` 只汇总叶子任务并跳过流式输出，中间节点的结果不会进入最终输出。
- 单任务批次实时流式输出、多任务批次延迟整块输出，两条路径的用户体验不一致。
- 任务冲突依赖 Planner 显式建边，没有自动资源冲突检测、写集分析或路径锁。
- 任务没有权重、优先级或关键路径估算，批次顺序仅由插入顺序与 DFS 后序决定。
- 节点副作用不会因后续失败自动回滚；用户可以结合 Side-Git 快照恢复 pre-turn 状态。
- Planner 的 JSON 结构仍受模型输出质量影响；未知任务类型静默回退为 `ANALYSIS`；简单目标类型推断存在 `&&`/`||` 优先级导致的误判。
- 计划摘要（`ExecutionPlan.summary`）只用于 `summarize()` 展示，不会注入任何任务 Prompt。
- 节点内的工具调用可见性与授权由 `TurnToolPolicy` 控制，依赖任务只继承**类型化**的 `web_search` URL，不继承任意工具结果。
