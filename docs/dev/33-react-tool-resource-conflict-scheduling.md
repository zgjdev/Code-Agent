# ReAct 同轮工具资源冲突调度

## 1. 背景、目标与非目标

### 背景

当前 `ToolRegistry.executeTools()` 对同一轮 LLM 返回的多个非 Browser 工具调用直接放入固定线程池并发执行，最多 4 个；结果按原始 tool call 顺序回传。Browser 批次已有整批串行保护。

Plan-and-Execute 路径已经通过 `TaskResourceClaims` + `ConflictAwareBatchSelector` 在 DAG Task 级别避免读写资源冲突，但 ReAct 的 Tool Call 不携带 Planner 生成的 `resources` 声明，因此不能直接复用“由 LLM/Planner 声明资源”的方式。

这会产生一个现实风险：同一轮如果模型同时生成 `write_file(A)` 与 `read_file(A)`、两个重叠路径写入，或 `execute_command` 与文件读写，它们会并行进入执行区，可能观察到中间态或相互覆盖。

### 目标

1. 在 Tool Call 真正执行前，由本地代码根据 `tool name + arguments` 确定性推导资源 claim，不增加任何 LLM 调用。
2. 对能精确判断的文件工具使用路径级 READ/WRITE claim；对无法静态确定影响范围的命令工具采用保守 workspace claim。
3. 将同轮调用切分为稳定、顺序保持的 conflict-aware batches；批内最多 4 并发，批间串行。
4. 保持 ToolExecutionResult 的原始 tool call 顺序、现有取消语义和现有 Browser 整批串行语义。
5. 对资源推导失败采取 fail-safe 降级：读工具扩大为 workspace read，写工具扩大为 workspace write，而不是让调度依赖模型猜测。

### 非目标

- 不做 Shell 命令静态分析，不尝试从 `mvn test` / `python script.py` 精确推导文件集合。
- 不修改 MCP 协议，也不要求第三方 MCP Server 提供资源元数据。
- 不把 ReAct Tool Call 改造成新的 DAG。
- 不改变 Plan Task 的 `TaskResourceClaims` 数据模型。

## 2. 现状分析（源码证据、已知约束）

### 2.1 架构位置

当前核心路径：

- `TurnToolPolicy` 负责授权、URL、路径作用域和 HITL 前置策略。
- `ToolRegistry.executeTools()` 负责同轮 Tool Call 的实际调度。
- 非 Browser 多调用当前统一通过 `ExecutorService.invokeAll(...)` 并发。
- `PathGuard.resolveSafe()` 已能把项目相对路径解析成规范化、安全绝对路径，并拒绝越界/符号链接逃逸。
- Plan 层的 `ConflictAwareBatchSelector` 已验证“写写冲突、写读冲突、目录/子路径重叠、workspaceWrite 独占”这一资源冲突模型。

### 2.2 数据/状态模型

新增 Tool 层本地资源模型：

```text
ToolResourceClaim
- readPaths: List<Path>
- writePaths: List<Path>
- workspaceRead: boolean
- workspaceWrite: boolean
- exclusiveResources: Set<String>
```

资源来源不是 LLM 声明，而是 `ToolResourceClaimResolver` 根据实际 Tool Call 参数推导。

首版内置工具映射：

| Tool | Claim |
|---|---|
| read_file(path) | READ(path) |
| write_file(path) | WRITE(path) |
| list_dir(path) | READ(path subtree) |
| glob_files(path) | READ(path subtree)，默认 path=. 时 WORKSPACE_READ |
| grep_code(path) | READ(path subtree)，默认 path=. 时 WORKSPACE_READ |
| search_code | WORKSPACE_READ |
| execute_command | WORKSPACE_WRITE |
| create_project(name) | WRITE(name subtree) |
| revert_turn | WORKSPACE_WRITE |
| web_search / web_fetch | none |
| load_skill | exclusive: skill-context |
| save_memory | exclusive: long-term-memory |
| browser_connect / browser_disconnect / browser_status | exclusive: browser-session |
| mcp__*（非 Browser） | exclusive: mcp-external |
| 未知非 MCP 名称 | none（实际执行仍会按原逻辑返回 TOOL_NOT_FOUND） |

Browser MCP 仍由现有 `TurnToolPolicy.isBrowserToolName(...)` 检测并走整批串行，不在本次行为变更中放宽。

### 2.3 核心时序与失败路径

```mermaid
sequenceDiagram
    participant L as LLM
    participant P as TurnToolPolicy
    participant R as ToolResourceClaimResolver
    participant S as ToolConflictAwareBatchSelector
    participant T as ToolRegistry
    L-->>P: 同轮 Tool Calls
    P-->>T: 已授权调用
    T->>R: name + arguments
    R-->>T: ToolResourceClaim
    T->>S: claims + 原始调用顺序
    S-->>T: 稳定 batches
    loop 每个 batch
        T->>T: 批内最多 4 并发
        T->>T: 等待当前 batch 完成
    end
    T-->>L: 按原始 Tool Call 顺序返回结果
```

资源推导失败时不阻断工具本身：

- 只读文件类工具路径无法安全解析：退化为 `workspaceRead=true`。
- 写入类工具路径无法安全解析：退化为 `workspaceWrite=true`。
- 实际 PathGuard/Policy 拒绝仍发生在原来的工具执行阶段，保持错误类型与审计行为不变。

## 3. 方案设计

### 3.1 接口与数据结构

新增：

- `com.codeagent.tool.ToolResourceClaim`
- `com.codeagent.tool.ToolResourceClaimResolver`
- `com.codeagent.tool.ToolConflictAwareBatchSelector`

`ToolResourceClaimResolver` 不调用 LLM，只读取 Tool Call 的 JSON 参数。文件路径统一复用当前 `PathGuard` 规范化，不复制另一套路由/安全校验逻辑。

冲突规则：

1. `workspaceWrite` 与任何 workspace read/write claim 冲突。
2. `workspaceRead` 与任意路径写或 `workspaceWrite` 冲突。
3. 路径 WRITE/WRITE 冲突。
4. 路径 WRITE/READ 冲突。
5. 路径重叠包括同一路径和祖先/后代路径。
6. 相同 `exclusiveResources` 冲突。
7. READ/READ 不冲突。

### 3.2 策略、安全、并发与恢复

采用“稳定前缀 batch”而不是跳过中间冲突项去挑后续调用：

```text
write A
read A
write B
```

调度为：

```text
Batch 1: write A
Batch 2: read A + write B   (二者资源不冲突)
```

这样后续调用不会越过前面的冲突调用，避免改变 tool call 的可观察顺序语义。

对于：

```text
write A
write B
```

如果路径不重叠则同批并发。

对于：

```text
read A
execute_command("mvn test")
```

`execute_command` 使用 `WORKSPACE_WRITE`，因此分批串行。

多 batch 继续共享一次 `executeTools` 的总 batch timeout 预算，避免因拆批把原本单个超时窗口放大为 N 倍。

### 3.3 兼容性、迁移与回滚

- 不修改 Tool Call JSON 协议。
- 不修改 LLM Prompt，不增加 token 成本。
- 不改变工具返回结构。
- Browser 批次行为不变。
- Plan DAG Task 级资源调度不变；Plan Task 内部同样通过统一 `ToolRegistry.executeTools()` 获得更细粒度的同轮保护。
- 回滚只需移除新增 claim/scheduler，并恢复 `executeTools()` 原非 Browser 并发路径。

## 4. 实现任务与测试矩阵

1. 先增加行为测试，证明当前冲突工具会错误并发：
   - 同一路径 `write_file + read_file` 必须串行。
   - `execute_command + read_file` 必须串行。
   - 不同路径 `write_file + write_file` 仍应并发。
   - 结果顺序保持原 Tool Call 顺序。
2. 增加 `ToolResourceClaim` 和 `ToolResourceClaimResolver`。
3. 增加 `ToolConflictAwareBatchSelector`，实现路径重叠与稳定前缀 batching。
4. 重构 `ToolRegistry.executeTools()` 使用批次调度并共享总 timeout deadline。
5. 同步 `AGENTS.md` 的工具并发约束。
6. 验证：
   - `mvn test -Dtest=ToolResourceSchedulingTest,ToolRegistryTest`
   - `mvn test -Pquick`
   - `git diff --check`（如执行环境可用）

## 5. 验收清单

- [ ] 不新增任何资源判断 LLM 调用。
- [ ] 文件路径 claim 来自 Tool Call 参数并经过 PathGuard。
- [ ] 同路径写读、重叠路径写写不会并发。
- [ ] `execute_command` / `revert_turn` 不与 workspace 文件访问并发。
- [ ] 不同路径写入仍可并发。
- [ ] 只读调用仍可并发。
- [ ] 未知 MCP 默认保守串行外部副作用。
- [ ] Browser 原整批串行语义不回退。
- [ ] 返回结果保持原 tool call 顺序。
- [ ] 取消和 timeout 行为无回归。
