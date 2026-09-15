# 持久化会话与短期记忆设计

> 状态：设计文档，基于当前 CodeAgent 代码、OpenAI Codex 开源仓库和固定版本的 DeepSeek Harness 源码整理。
>
> 目标：服务重启或异常退出后，恢复可继续对话的短期上下文，同时不牺牲原始审计记录、压缩可追溯性和 token 估算安全性。

## 1. 结论先行

当前项目的 `ConversationLedger` 已经持久化原始 LLM 消息，但它只是审计/导出账本：启动时 `Main` 创建新的 session ledger，Agent 的 `conversationHistory` 仍然是进程内列表，启动时没有 replay 账本恢复活动上下文。因此，当前进程退出后虽然原始 JSONL 还在，下一次启动不能自动继续上一段对话；provider usage anchor 也会丢失。

正确的目标不是“把 `conversationHistory` 定期序列化”，而是建立：

```text
append-only session log（唯一事实源）
        |
        +--> replay / projection --> active conversationHistory
        +--> compaction state
        +--> request header / usage facts
        +--> checkpoint（仅用于加速恢复）
```

`conversationHistory` 是发送视图（active surface），可以因 `/clear`、图片裁剪或压缩而改变；原始日志永不改写。任何发送视图都必须能从日志 replay 重建，不能维护一个无法解释来源的第二份短期记忆。

## 2. 开源方案的可验证依据

### 2.1 DeepSeek Harness

本项目已有调研固定到 `deepseek-ai/deepseek-harness` commit `c291e7961a515f6d7af9304e7fd1d257929aef26`。其 session 类型明确区分：

- append-only `SessionEvent` 是交互的 source of truth；消息 history 由事件派生。
- `request/header` 单独记录 provider/model/call config/tools；system prompt 是 surface 上的 `system/message`，不是 header。
- `system/message`、`user/message`、`assistant/message`、`tool/result` 带 `surfaceOp`；普通消息 append，压缩可用 `replace(startSeq,endSeq)` 替换一段 surface。
- `assistant/message` 携带本次 provider usage；失败或取消的模型尝试用 `assistant/attempt` 保留，但不伪造可见 assistant 消息。
- `turn/start`、`step/start`、`step/end`、`turn/end` 表达生命周期；崩溃后未闭合 turn 可恢复为 `interrupted`。
- session format 有单调版本；未知且会影响重建的事件必须拒绝恢复，只有显式标为 ignorable 的事件才能跳过。

这意味着压缩不会删除旧消息，而是追加摘要和 surface replacement；重启时 replay 得到最新 active surface，同时保留原始内容和 usage 事实。

### 2.2 OpenAI Codex

本次核对的开源仓库为 `openai/codex` commit `7f01a84effccef40d4726c3ca12e6c839ec98d7a`；其 `codex-rs/core` 可直接看到：

- `compact.rs`、`compact_remote_history.rs`、`compact_token_budget.rs` 将自动/手动/远程压缩建模为独立生命周期，并发出 `ContextCompaction` turn item。
- `rollout_reconstruction.rs` 从持久 rollout 重建 history、compaction window、world-state 和 resume 元数据；不是依赖进程内列表。
- `ContextCompactedNotification` 和 `ThreadTokenUsageUpdatedNotification` 分别传递压缩状态与 token usage，二者没有合并成一个数值。
- 超长时 Codex 还有对 function-call output 的确定性截断路径；压缩/截断都是可观察、可恢复的状态变化。

Codex 的模块名不能证明其所有内部阈值或存储格式都适用于本项目，但可以确认其架构原则：持久 session/rollout、history reconstruction、compaction lifecycle、usage projection 分离。

## 3. 持久化对象模型

### 3.1 Session 元数据

每个可恢复会话建立独立目录：

```text
~/.codeagent/history/sessions/<session-id>/
  manifest.json
  events.jsonl
  checkpoints/
    checkpoint-<seq>.json
```

`manifest.json` 保存：

| 字段 | 说明 |
|---|---|
| `schemaVersion` | session 存储格式版本，单调递增 |
| `sessionId` | 安全字符集 ID |
| `workspace` | 创建时项目根路径 |
| `provider` / `model` | 最近一次路由，仅用于展示和恢复校验 |
| `createdAt` / `updatedAt` | 时间戳 |
| `parentSessionId` | SubAgent/分支会话的父会话，可选 |
| `closed` | 是否正常关闭；异常退出保持 false |
| `lastEventSeq` | 最后成功写入的序号 |

manifest 不是事实源。损坏或落后时，以 `events.jsonl` 的完整 replay 结果为准。

### 3.2 原始事件

现有 `ConversationLedger.Entry` 应扩展为可恢复事件，至少覆盖：

- `session/start`、`session/end`、`session/interrupt`
- `turn/start`、`turn/end`，其中 `turn/end.reason` 可为 `completed`、`failed`、`aborted`、`interrupted`
- `request/header`：provider、model、推理/采样配置、工具 schema 的 canonical 快照
- `system/message`、`user/message`、`assistant/message`、`assistant/reasoning`
- `tool/call`、`tool/result`，包含 invocation id、参数、结果、错误和执行状态
- `image/attached` 或图片引用元数据；大 payload 用受控附件文件并在事件中保存 hash/path
- `provider/usage`：原始 input/output/cache/reasoning 字段、scope、可信度和对应 request id
- `request/started`、`request/snapshot`、`request/finished`、`request/failed`
- `surface/clear`、`surface/replace`、`image/pruned`
- `compaction/start`、`compaction/summary`、`compaction/replace`、`compaction/end`
- `error`、`retry/start`、`retry/end`

每个事件都包含 `schemaVersion`、session id、连续 `sequence`、时间、事件类型、mode/actor/source 和结构化 payload。事件追加后 `FileChannel.force(false)`，再允许对应的内存状态作为已提交状态对外可见。

### 3.3 活动 surface

surface 是下一次请求实际可见的消息序列，不是另一份独立历史：

- 普通 system/user/assistant/tool 消息使用 `append`。
- `/clear` 追加 `surface/clear`，再追加新的 system message。
- 图片裁剪追加 `image/pruned` 或 `surface/replace`，原始图片事件仍保留。
- 压缩摘要以一个新的可见节点替换 `[startSeq,endSeq]`，replacement 记录被替换事件范围和摘要 hash。
- replay 按 sequence 应用 append/clear/replace，得到唯一 `activeSurface`，再转换为 Agent 的 `conversationHistory`。

## 4. 写入时机与提交语义

### 4.1 正常 LLM 请求

一次模型调用按以下顺序提交：

1. 将已确定的 request snapshot（完整 system、history、tools、provider/model、图片/资源引用）追加为 `request/started` 和 `request/snapshot`。
2. 请求开始前追加本轮 user/injected messages；这些事件落盘成功后才能发送请求。
3. 收到完整响应并确认 SSE `[DONE]` 或非空 `finish_reason` 后，追加 assistant message、tool call 和 `provider/usage`，然后追加 `request/finished`。
4. 只有第 3 步成功后，才把本次 assistant/tool 状态视为 committed surface。

如果流在中途断开：保留 `request/started` 和已经收到的诊断/attempt 信息，追加 `request/failed` 或 `turn/end(interrupted)`；不得把未完成 assistant 消息当成完整上下文。若已向用户展示部分文本，应记录为 `assistant/message(interrupted=true)`，并明确它不是可重放的完整工具调用。

### 4.2 工具调用

每个 tool call 必须有稳定 `invocationId`：

```text
tool/call(invocationId, name, argsHash, args)
tool/execution-started(invocationId)
tool/result(invocationId, status, result/error)
```

恢复时，只有存在 committed `tool/result` 的调用才进入 active surface。只有 `tool/call` 没有 result 的调用标为 `pending/interrupted`，默认不自动重放；具有副作用的工具必须通过 invocation idempotency key 防止重启后重复执行。只读工具可由明确恢复策略选择重试。

## 5. 重启、崩溃与恢复

### 5.1 启动恢复

新增 `SessionStore.open(sessionId)` 和 `SessionReplayer.replay(...)`：

1. 读取 manifest，校验 session id、workspace 和 schema version。
2. 找到最新有效 checkpoint；若不存在或 hash/sequence 不匹配，从 events.jsonl 开始 replay。
3. 顺序校验 sequence 连续性；末尾不完整 JSON 行视为未提交尾部并隔离，不覆盖原文件。
4. 应用 surface append/clear/replace/prune，恢复 active conversationHistory。
5. 恢复最近一次完整 provider usage 作为诊断事实；恢复 compaction generation、history version 和未完成 turn 状态。
6. 对未完成 request/turn 追加 `session/interrupt`（幂等），向用户显示“上次请求中断，可继续”，不得自动执行未确认的副作用工具。
7. 下一次请求必须先做完整本地 estimate；只有新请求成功后，基于新的 envelope 建立 usage anchor。旧 anchor 不能仅因为 session replay 成功就直接复用。

### 5.2 usage anchor 的安全规则

持久化 `provider/usage` 是事实记录，不等于可跨重启复用的 anchor。只有以下条件全部满足才可作为新请求的增量基线：

- provider、model、call config 完全相同；
- canonical tools schema hash 相同；
- active surface 的 history version/compaction generation 与 measurement 对齐；
- 上一次响应完整结束，usage 字段 scope 明确且通过可信度校验；
- request envelope 能证明与当前请求的基线可比较。

任何条件不满足，使用当前完整 surface estimate。estimate 只用于安全边界和压缩判断；provider usage 仍作为上一请求的实际事实保存，不混入累计计费数字。

## 6. Checkpoint 与文件一致性

事件日志是唯一权威源，checkpoint 只是加速恢复的缓存。建议：

- 每 50 个事件或每次 `compaction/end` 后异步生成 checkpoint；
- checkpoint 包含 `lastAppliedSeq`、active surface、history version、compaction generation、最近 usage 摘要和文件 hash；
- 先写 `checkpoint.tmp`，`force` 后原子 rename/replace；
- 事件始终先 append+force，再更新 checkpoint；checkpoint 写失败不影响会话继续；
- 同一 session 使用 JVM 文件锁/`FileChannel.lock()`，防止两个进程同时追加；检测到锁占用时只读打开或明确报错；
- Windows 无 POSIX 权限时使用默认 ACL，并在文档和启动日志提示日志包含敏感内容。

## 7. `/clear`、`/compact`、`/resume`

### `/clear`

不删除 raw events，不创建新 session。追加 `surface/clear` 和新的 system message，递增 history version，清空内存 surface，并立即使 usage anchor 失效。重启 replay 后 active surface 仍为空（只保留新 system），但 `/export` 仍可审计旧内容。

### `/compact`

按事务式生命周期执行：

```text
compaction/start
  -> 生成摘要
  -> 校验摘要非空且严格小于被替换范围
  -> append compaction/summary
  -> append surface/replace(startSeq,endSeq,summarySeq)
  -> compaction/end
  -> checkpoint
```

在 `compaction/end` 之前崩溃，replay 不应用未完成 replacement，继续使用旧 surface；摘要生成失败也不能破坏原始上下文。压缩完成后递增 generation、失效旧 anchor，并从新 surface 重新测量。

### `/resume`

新增 `/sessions` 列出最近 session 的 id、workspace、更新时间和是否中断；`/resume <id>` 打开指定 session，执行上述 replay。默认只恢复同一 workspace；跨 workspace 必须显式确认。恢复后显示“已恢复 N 条可见消息，M 个未完成工具调用未自动执行”。

## 8. 多 Agent 隔离

ReAct 主会话、Plan task、SubAgent 和 Team worker 不能共用一个可写 active surface。每个 agent 使用独立 session id/事件日志；父子关系写入 `parentSessionId`。需要把子任务结果交给父会话时，父会话追加一个普通 user/injected message，并记录来源 session id，而不是直接拼接或共享两个 `conversationHistory` 对象。

## 9. Token 与压缩判断

重启恢复后，压缩判断仍沿用现有混合方案，但输入必须来自 replay 后的完整 active surface：

```text
surfaceEstimate = estimate(system + messages + tools + images + resources)

if trustedAnchorMatches(currentEnvelope):
    projected = anchorProviderUsage +
                (surfaceEstimate - anchorSurfaceEstimate)
else:
    projected = surfaceEstimate

shouldCompact = projected >= modelContextWindow
                 - reservedSummaryOutput
                 - safetyBuffer
```

不要把 provider 的 output token 直接当作下一轮输入；只有当该 output 已经作为 assistant/tool 消息进入 replay 后，它才通过当前 surface 的完整 estimate 体现。`inputTokens`、`outputTokens`、`cachedInputTokens`、累计费用和 projected context 是不同指标，分别持久化和展示。

压缩触发前必须完成本轮 user prompt 展开、工具 schema 固化、资源/图片处理和工具结果落盘。provider 返回 context overflow 时走独立 recovery：先对可裁剪 tool result 做确定性缩短，再尝试摘要；只有 replacement 真正改变 surface 才重试，最多一次，避免估算偏差导致死循环。

## 10. 迁移与兼容

现有 `~/.codeagent/history/raw/session-*.jsonl` 作为 legacy 输入保留。首次打开旧文件时：

1. 只读解析旧 `ConversationLedger.Entry`；
2. 生成新 session 目录和 `migration/start`；
3. 将可识别 message/event 映射为新事件；
4. 对无法确认提交边界的尾部标为 `legacy/interrupted`，不自动执行工具；
5. 写入 `migration/end` 和 checkpoint；
6. 原 raw 文件不删除，迁移可重复且幂等。

当事件结构或 surface 语义改变时递增 `schemaVersion`。未知事件若可能影响 surface 重建必须拒绝 resume 并保留只读 export；仅带 `ignorable=true` 的诊断事件允许跳过。

## 11. 实现拆分与验收标准

建议按以下顺序实现：

1. 将 `ConversationLedger` 拆为 `SessionStore`、`SessionEvent`、`SessionReplayer`，先支持现有消息的 append/replay。
2. 增加 surface 操作和 `/clear` replay，验证重启后消息序列与退出前一致。
3. 增加 request lifecycle、usage facts、未完成请求和 tool invocation 状态。
4. 增加 compaction replacement、checkpoint、原子写和文件锁。
5. 接入 `Main` 的 session list/resume，并让 ReAct/Plan/SubAgent 分配独立 session。
6. 最后接入持久 usage 诊断和重启后的 anchor 安全校验。

最低验收用例：

- 正常退出/重启后，system、user、assistant、tool call/result 的 active surface 完全一致。
- `/clear` 后重启不恢复旧可见消息，但 `/export` 仍包含旧 raw events。
- 压缩完成后重启得到摘要+tail；压缩中断重启仍得到旧 surface。
- 流式响应中断不会伪造完整 assistant/tool call；未完成副作用工具不会自动重放。
- provider usage 缺失、scope 不明、model/tools 改变或 checkpoint 损坏时，下一请求走完整 estimate。
- 同一事件日志重复 replay 结果稳定；重复执行 migration/checkpoint 不产生重复消息。
- 父子 Agent session 相互隔离，子结果只通过父 session 的显式事件回灌。

## 12. 与当前实现的边界

当前已经存在并应保留：`ConversationLedger` 的 append-only 原始记录、`ContextTokenTracker` 的 provider usage anchor/surface delta、`ConversationHistoryCompactor`、`/clear` 与 `/compact` 的内存行为。

当前尚未实现、本文建议新增：session 可选恢复入口、ledger replay 到 active surface、结构化 surface replacement、request/attempt 提交边界、tool invocation 幂等状态、checkpoint、migration 和跨重启 usage anchor 校验。实现完成前不能声称“短期记忆已经持久化恢复”。
