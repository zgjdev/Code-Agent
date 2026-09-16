# 持久化会话与短期记忆实现计划

> **执行状态（2026-09-16）：** 本计划已按任务顺序实现并提交（`4af739c` 至 `39e8720`）。下方清单保留为设计审计记录；勾选项表示对应实现已交付，验证结果以文末“验收记录”为准。
>
> **原执行要求：** 按任务顺序实施，每个任务先写失败测试，再写最小实现。每完成一个任务运行该任务的定向测试；全部完成后运行 `mvn test -Pquick` 和全量测试。

**目标：** 将当前仅存在于内存的 `conversationHistory` 改造成可由持久 session event log 重建的活动上下文，使正常重启、异常退出、上下文压缩和 `/clear` 之后都能得到确定、可审计、可继续对话的状态。

**架构：** `events.jsonl` 是唯一事实源；`SessionReplayer` 将事件投影为 `SessionProjection.activeSurface`，Agent 的 `conversationHistory` 只是该投影的内存发送视图。checkpoint 仅加速 replay，损坏后必须能由完整事件日志重建。原始消息永不因 `/clear`、图片裁剪或 compaction 被删除。

**技术栈：** Java 17、Jackson 2.16、JUnit 5、NIO `FileChannel`、JSONL；不新增数据库和第三方持久化依赖。

**设计基线：** `docs/dev/09-persistent-session-and-short-term-memory.md`。

## 全局约束

- 代码实际行为优先；修改 Memory 必须同步 `AGENTS.md`、`README.md` 和相关测试。
- 不引入 `MemoryManager.shortTermMemory` 或其他影子消息列表。
- `events.jsonl` 只追加，不原地改写，不因压缩、清空或图片裁剪删除旧事件。
- 日志可能包含完整 prompt、reasoning、工具参数/结果和图片，POSIX 目录权限保持 `0700`、文件保持 `0600`。
- Windows 使用系统 ACL；不得把 session 文件写进项目目录或 Git。
- 同一 session 同时只允许一个可写进程；第二个进程只能失败或显式只读打开。
- 恢复不自动重放未完成的副作用工具调用。
- 恢复后的第一次 LLM 请求使用完整本地估算；成功后才重新建立 `ContextTokenTracker` usage anchor。
- 第一期保留旧 `~/.codeagent/history/raw/*.jsonl`，迁移成功后也不删除。
- 所有事件使用连续、从 0 开始的 `sequence`；未知的非 ignorable 事件必须拒绝恢复。

---

## 1. 最终目录和类职责

### 1.1 磁盘布局

```text
~/.codeagent/history/
├── raw/                              # 旧 ConversationLedger v1，只读兼容
│   └── session-*.jsonl
└── sessions/
    └── <session-id>/
        ├── manifest.json
        ├── events.jsonl
        ├── session.lock
        ├── attachments/
        │   └── <sha256>.<ext>
        └── checkpoints/
            └── checkpoint-<sequence>.json
```

### 1.2 新增文件

| 文件 | 单一职责 |
|---|---|
| `history/SessionEvent.java` | 定义稳定事件 envelope、事件类型、surface 操作及 payload |
| `history/SessionManifest.java` | session 元数据和格式版本 |
| `history/SessionProjection.java` | replay 的不可变输出，不执行 IO |
| `history/SessionReplayer.java` | 纯函数式校验并投影事件 |
| `history/SessionStore.java` | session 创建、打开、列出、追加、锁和 manifest 原子更新 |
| `history/SessionCheckpointStore.java` | checkpoint 校验、读取和原子写 |
| `history/LegacySessionMigrator.java` | 旧 `raw/*.jsonl` 到新事件格式的幂等迁移 |
| `history/SessionSummary.java` | `/sessions` 展示所需的轻量元数据 |
| `history/SessionResumeResult.java` | 恢复结果、警告、未完成请求/工具调用 |

### 1.3 修改文件

| 文件 | 修改内容 |
|---|---|
| `history/ConversationLedger.java` | 暂时作为兼容 facade，内部委托 `SessionStore`；保留现有公共方法直至所有调用点迁完 |
| `agent/Agent.java` | 注入恢复后的 surface；所有消息和 surface 变化 durable-first；记录 request/turn 生命周期 |
| `agent/PlanExecuteAgent.java` | 使用独立 child session，向父 session 只回灌结果引用 |
| `agent/SubAgent.java` | 使用独立 child session，禁止与父 Agent 共用可写 ledger |
| `agent/AgentOrchestrator.java` | 分配父子 session id 并记录 lineage |
| `context/ContextTokenTracker.java` | 增加明确的 restart invalidation；不直接恢复旧 anchor |
| `memory/AutoCompactionManager.java` | 返回压缩前后消息和替换范围，自己不直接持久化 |
| `memory/ConversationHistoryCompactor.java` | 输出 `CompactionOutcome`，先验证收益，再由 Agent 提交 replacement |
| `cli/CliCommandParser.java` | 增加 `/sessions`、`/resume <id>`、`/new` |
| `cli/CodeAgentCompleter.java` | 补全 session 命令和最近 session id |
| `cli/Main.java` | session 启动策略、命令处理、Agent 切换和退出关闭 |
| `README.md`、`AGENTS.md` | 更新短期上下文持久化、命令和安全说明 |

## 2. 事件协议

### 2.1 Envelope

新增 `SessionEvent.java`：

```java
public record SessionEvent(
        int schemaVersion,
        String sessionId,
        long sequence,
        long timestamp,
        String type,
        String mode,
        String actor,
        String source,
        boolean ignorable,
        SurfaceOperation surface,
        JsonNode payload) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public static final class Types {
        public static final String SESSION_START = "session/start";
        public static final String SESSION_END = "session/end";
        public static final String SESSION_INTERRUPT = "session/interrupt";
        public static final String TURN_START = "turn/start";
        public static final String TURN_END = "turn/end";
        public static final String REQUEST_STARTED = "request/started";
        public static final String REQUEST_SNAPSHOT = "request/snapshot";
        public static final String REQUEST_FINISHED = "request/finished";
        public static final String REQUEST_FAILED = "request/failed";
        public static final String SYSTEM_MESSAGE = "system/message";
        public static final String USER_MESSAGE = "user/message";
        public static final String ASSISTANT_MESSAGE = "assistant/message";
        public static final String TOOL_CALL = "tool/call";
        public static final String TOOL_EXECUTION_STARTED = "tool/execution-started";
        public static final String TOOL_RESULT = "tool/result";
        public static final String PROVIDER_USAGE = "provider/usage";
        public static final String SURFACE_CLEAR = "surface/clear";
        public static final String IMAGE_PRUNED = "image/pruned";
        public static final String COMPACTION_START = "compaction/start";
        public static final String COMPACTION_SUMMARY = "compaction/summary";
        public static final String COMPACTION_END = "compaction/end";
        public static final String LEGACY_EVENT = "legacy/event";

        private Types() {}
    }
}
```

事件类型必须使用字符串而不是严格 enum。否则较新版本写入一个 `ignorable=true` 的未知事件时，旧版 Jackson 会在读取 `ignorable` 之前就因 enum 值未知而失败，破坏协议的向前兼容承诺。

`SurfaceOperation` 使用显式操作，不从 `source` 字符串猜语义：

```java
public sealed interface SurfaceOperation
        permits SurfaceOperation.Append, SurfaceOperation.Replace,
                SurfaceOperation.Clear, SurfaceOperation.None {
    record Append() implements SurfaceOperation {}
    record Replace(long startSequence, long endSequence) implements SurfaceOperation {}
    record Clear() implements SurfaceOperation {}
    record None() implements SurfaceOperation {}
}
```

JSON 中 `surface` 形态固定为：

```json
{"op":"append"}
{"op":"replace","startSequence":12,"endSequence":48}
{"op":"clear"}
{"op":"none"}
```

### 2.2 Payload 约束

消息事件 payload：

```json
{
  "message": {
    "role": "assistant",
    "content": "...",
    "reasoningContent": "...",
    "toolCalls": [],
    "toolCallId": null,
    "contentParts": null
  },
  "turnId": "turn-7",
  "requestId": "request-13",
  "interrupted": false
}
```

`TOOL_CALL` 和 `TOOL_RESULT` payload：

```json
{
  "invocationId": "call_abc",
  "requestId": "request-13",
  "name": "write_file",
  "arguments": "{\"path\":\"README.md\"}",
  "argumentsSha256": "..."
}
```

```json
{
  "invocationId": "call_abc",
  "status": "success",
  "result": "...",
  "error": null
}
```

`PROVIDER_USAGE` payload 使用现有 `MeasuredUsage` 语义，不另造“总上下文”字段：

```json
{
  "requestId": "request-13",
  "provider": "deepseek",
  "model": "deepseek-chat",
  "inputTokens": 10240,
  "outputTokens": 640,
  "cachedInputTokens": 8192,
  "inputScope": "TOTAL_PROMPT",
  "includesTools": true,
  "includesSystem": true,
  "trusted": true,
  "measuredAt": "2026-09-16T08:00:00Z"
}
```

`REQUEST_SNAPSHOT` 只保存可比较事实和 fingerprint，不复制第二份完整消息历史：

```json
{
  "requestId": "request-13",
  "provider": "deepseek",
  "model": "deepseek-chat",
  "callConfigFingerprint": "...",
  "toolSchemaFingerprint": "...",
  "surfaceFingerprint": "...",
  "systemPromptFingerprint": "...",
  "estimatedSurfaceTokens": 9800,
  "estimatedToolTokens": 400,
  "historyVersion": 37,
  "compactionGeneration": 2
}
```

### 2.3 提交边界

- `SYSTEM_MESSAGE`、`USER_MESSAGE`、`ASSISTANT_MESSAGE`、`TOOL_RESULT` 才能成为 surface 节点。
- `TOOL_CALL` 是 assistant message 中 tool calls 的审计展开，不额外生成一条 LLM message，避免 replay 后重复 tool call。
- `ASSISTANT_MESSAGE` 只有在响应正常完成时使用 `interrupted=false`；replay 将它暂存到对应 request，直到看到 `REQUEST_FINISHED` 才提交到 surface。
- 流中断后若已有内容展示，保存 `interrupted=true` 的 assistant 消息用于 transcript，但使用 `surface=none`；不得将其作为下一轮完整 assistant 消息。
- `REQUEST_FINISHED` 表示响应和 usage 已完整结算；没有该事件的 request 视为中断，其中的 assistant/usage 不进入 committed projection。
- compaction replacement 只有存在匹配的 `COMPACTION_END(status=completed)` 时生效。

## 3. Replay 规则

### 3.1 投影结果

`SessionProjection.java`：

```java
public record SessionProjection(
        List<SurfaceNode> activeSurface,
        long lastAppliedSequence,
        long historyVersion,
        long compactionGeneration,
        Optional<MeasuredUsageFact> lastCompletedUsage,
        Set<String> incompleteRequestIds,
        Map<String, PendingToolInvocation> pendingTools,
        boolean cleanlyClosed,
        List<String> warnings) {

    public List<LlmClient.Message> messages() {
        return activeSurface.stream().map(SurfaceNode::message).toList();
    }
}

public record SurfaceNode(long sequence, LlmClient.Message message) {}
```

`SessionReplayer` 接口：

```java
public final class SessionReplayer {
    public SessionProjection replay(SessionManifest manifest,
                                    List<SessionEvent> events);

    public SessionProjection replayFrom(SessionProjection checkpoint,
                                        List<SessionEvent> tail);
}
```

### 3.2 确定性算法

按 `sequence` 升序执行：

1. 验证 session id 一致、sequence 连续、schema 可读。
2. 未知 `ignorable=false` 事件立即抛出 `UnsupportedSessionEventException`。
3. `append` 将消息节点加入尾部；同 sequence 重复视为损坏。
4. `clear` 清空所有活动节点；事件本身不成为消息。
5. `replace(start,end)` 要求 start/end 都是当前 surface 节点且 start 不晚于 end；将闭区间替换成当前消息事件。
6. compaction 的 summary/replace 先暂存在 pending transaction；看到同一 `compactionId` 的 completed end 后一次性应用。
7. `REQUEST_STARTED` 放入 incomplete set，`REQUEST_FINISHED`/`REQUEST_FAILED` 移除。
8. `TOOL_CALL` 放入 pending map，`TOOL_RESULT` 移除。恢复可写 session 时，对每个没有 result 的调用持久化一个 `status=interrupted` 的合成 tool result，告诉模型该工具没有重试；禁止直接重放工具副作用，也不能把不配对的 assistant tool-call 留给下一次 provider 请求。
9. `SESSION_END` 设置 cleanlyClosed；之后若还有事件则重新设为 false。
10. replay 不写磁盘；对未闭合状态只返回 warnings，由 `SessionStore.resumeWritable()` 决定是否追加 interrupt 事件。

### 3.3 尾部损坏

读取 `events.jsonl` 时逐行解析：

- 中间行 JSON 损坏：拒绝恢复，允许只读 export。
- 最后一行没有换行且 JSON 不完整：视为崩溃写入尾部，忽略该行并返回 warning。
- 最后一行 JSON 完整但没有换行：接受该事件。
- 绝不自动截断或覆盖原文件；修复工具属于后续独立功能。

## 4. SessionStore 接口

```java
public final class SessionStore implements AutoCloseable {
    public static SessionStore openDefault(Path userHome) throws IOException;

    public SessionHandle create(SessionCreateRequest request) throws IOException;
    public SessionHandle resumeWritable(String sessionId, Path workspace) throws IOException;
    public SessionProjection readProjection(String sessionId) throws IOException;
    public List<SessionSummary> list(Path workspace, int limit) throws IOException;
    public Optional<SessionSummary> latestUnclosed(Path workspace) throws IOException;
    public Optional<SessionSummary> latest(Path workspace) throws IOException;
}

public interface SessionHandle extends AutoCloseable {
    String sessionId();
    SessionManifest manifest();
    SessionProjection projection();
    SessionEvent append(SessionEventDraft event) throws IOException;
    List<SessionEvent> readAll() throws IOException;
    void markClosed(String reason) throws IOException;
}
```

`SessionHandle.append` 必须在一个 synchronized 临界区内完成：分配 sequence、序列化一行、append、`force(false)`、更新内存 projection、原子更新 manifest。日志 append/force 失败时不得改变 projection，也不得让 Agent 先改变 `conversationHistory`。日志已经 force 成功而 manifest 更新失败时，事件仍然是 committed；返回成功并记录 warning，下次打开时由 event log 修复 manifest，不能否定已经持久化的事实。

`ConversationLedger` 过渡接口：

```java
@Deprecated(forRemoval = true)
public final class ConversationLedger {
    private final SessionHandle handle;

    public SessionEvent appendMessage(...);
    public SessionEvent appendEvent(...);
    public List<Entry> readAll();
}
```

调用点迁移完成后再删除 facade，不能在第一步同时删除旧 API 和引入全部新行为。

## 5. Agent 接入规则

### 5.1 构造与恢复

不要继续使用“构造 Agent 后 attach ledger 并重复写一条 system”的模式。新增：

```java
public Agent(LlmClient llmClient,
             ToolRegistry toolRegistry,
             SessionHandle session,
             SessionProjection projection) {
    // projection 为空才构造并持久化初始 system；非空直接恢复 messages。
}

public void restoreProjection(SessionProjection projection) {
    conversationHistory.clear();
    conversationHistory.addAll(projection.messages());
    historyVersion = projection.historyVersion();
    contextTokenTracker.invalidate(InvalidationReason.SESSION_RESTORED);
    autoCompactionManager.clearPreparedState();
    skillContextBuffer.clear();
}
```

给 `InvalidationReason` 增加 `SESSION_RESTORED`。即使 projection 中存在可信 usage，第一轮仍为 `FULL_ESTIMATE`。

### 5.2 Durable-first 消息追加

将当前 `appendConversationMessage` 改为：

```java
private void appendConversationMessage(LlmClient.Message message, String source) throws IOException {
    SessionEvent event = session.append(SessionEventDraft.message(
            currentMode, actorName, source, message, new SurfaceOperation.Append()));
    conversationHistory.add(message);
    historyVersion = session.projection().historyVersion();
}
```

不能先 `conversationHistory.add()` 再写日志。持久化失败时，本轮请求终止并向用户报告“会话状态无法保存，未继续调用模型”。

### 5.3 一次 ReAct step 的事件顺序

```text
TURN_START(turnId)
USER_MESSAGE append                         # 顶层用户输入，只写一次
REQUEST_STARTED(requestId, turnId, step)
REQUEST_SNAPSHOT(requestId, fingerprints)
    调用 provider
ASSISTANT_MESSAGE append                    # 暂存，REQUEST_FINISHED 后提交
PROVIDER_USAGE(requestId)
REQUEST_FINISHED(requestId)
    若有 tools：
      TOOL_CALL(invocationId)               # 审计，不二次加入 surface
      TOOL_EXECUTION_STARTED(invocationId)
      TOOL_RESULT append(invocationId)
    下一 step
TURN_END(completed)
```

异常路径：

```text
REQUEST_STARTED
REQUEST_SNAPSHOT
    provider/stream 失败
ASSISTANT_MESSAGE(surface=none, interrupted=true)   # 仅当已有可见片段
REQUEST_FAILED
TURN_END(failed|aborted|interrupted)
```

现有有限网络重试仍由 `LlmRetryPolicy` 负责；每个实际 provider attempt 追加 `RETRY_START/END`，但同一逻辑 request id 不变。

如果进程在已提交 assistant tool-call 后、`TOOL_RESULT` 前崩溃，resume 不执行原工具，而是为该 invocation id 追加一个合成的 interrupted tool result。这样既避免副作用重复，又保持下一次请求中的 tool-call/tool-result 协议完整。

### 5.4 System prompt 刷新

长期记忆 top-k 每轮可能改变 system prompt。刷新时追加新的 `SYSTEM_MESSAGE`，使用单节点 replacement：

```json
{"op":"replace","startSequence":0,"endSequence":0}
```

实际实现不能假定 system 永远是 sequence 0，应从 projection 查找当前 active system node sequence。新 system 文本作为 surface delta 参与现有混合 token 预测，不使 envelope 自动失配。

### 5.5 `/clear`

执行顺序：

1. 生成新的基础 system message，但暂不修改内存。
2. append `SURFACE_CLEAR`。
3. append 新 `SYSTEM_MESSAGE(surface=append)`。
4. 两次 append 成功后，用 handle 最新 projection 替换 `conversationHistory`。
5. 清除 Session Memory 预计算状态和 Skill buffer。
6. `ContextTokenTracker.invalidate(CLEAR)`。

如果第 2 步成功、第 3 步失败，重启后得到空 surface；当前进程也必须同步为该 projection，不能保留旧内存历史。下一次普通请求前可补写新的 system message。

### 5.6 Compaction

Compactor 不再直接原地修改传入列表，改为返回：

```java
public record CompactionOutcome(
        boolean compacted,
        List<LlmClient.Message> replacementMessages,
        int replacedFromIndex,
        int replacedToIndex,
        long beforeTokens,
        long afterTokens,
        String summary,
        String error) {}
```

Agent 根据当前 `SurfaceNode` 把 index 转成 start/end sequence，然后持久化：

```text
COMPACTION_START(compactionId,startSeq,endSeq)
COMPACTION_SUMMARY(compactionId,summary,beforeTokens,afterTokens)
USER_MESSAGE(surface=replace(startSeq,endSeq), summary wrapper)
ASSISTANT_MESSAGE(surface=append, acknowledgement)
COMPACTION_END(compactionId,status=completed)
```

为保证事务投影，summary replacement 和 acknowledgement 在 `COMPACTION_END` 前都暂存，不进入 committed projection。`afterTokens >= beforeTokens` 时记录 `COMPACTION_END(status=no-benefit)`，不改变 surface。

完成后用最新 projection 重建同一个 `conversationHistory`，递增 compaction generation，并 invalidate `COMPACTION`。自动压缩、手动 `/compact` 和 overflow recovery 共用这一提交函数。

## 6. CLI 行为

### 6.1 命令

`CliCommandParser.CommandType` 新增：

```java
SESSIONS,
RESUME_SESSION,
NEW_SESSION
```

解析规则：

| 输入 | 结果 |
|---|---|
| `/sessions` | 当前 workspace 最近 20 个会话 |
| `/resume` | 等价 `/resume last` |
| `/resume last` | 恢复当前 workspace 最近会话 |
| `/resume <session-id>` | 恢复指定同 workspace 会话 |
| `/new` | 正常关闭当前会话并创建新会话 |
| `/clear` | 清当前 session 的 active surface，不创建新 session |

`/resume` 只允许 Agent idle 时执行。若 session id 不存在、workspace 不匹配、被其他进程加写锁或 schema 不兼容，保持当前 session 不变。

### 6.2 启动策略

默认行为：

1. 查找当前 workspace 最新 `closed=false` 的 session。
2. 存在时自动恢复，并打印一次：`已恢复异常中断的会话 <id>，可见消息 N 条；未完成工具调用不会自动执行。`
3. 不存在时创建新 session。
4. 正常收到 `/exit` 或 EOF 时 append `SESSION_END` 并把 manifest.closed 设为 true。
5. 已正常关闭的历史会话不会自动恢复，但可用 `/resume last` 显式继续；resume 后追加 `SESSION_START(source=resume)` 并把 closed 改为 false。

系统属性/环境变量：

```text
codeagent.session.resume = auto | off
CODEAGENT_SESSION_RESUME = auto | off
```

默认 `auto` 只恢复同 workspace 的异常中断 session。`off` 总是新建，但不删除历史。

### 6.3 切换原子性

`/resume` 采用 prepare-then-swap：先完整打开、校验和 replay 目标 session，成功后再关闭当前 handle 并替换 Agent。任何准备失败都不得清空当前 `conversationHistory`。

## 7. Checkpoint

checkpoint schema：

```java
public record SessionCheckpoint(
        int schemaVersion,
        String sessionId,
        long lastAppliedSequence,
        String eventPrefixSha256,
        SessionProjection projection,
        long createdAt) {}
```

生成条件：

- 每累计 50 个 committed 事件；或
- 每次 completed compaction；或
- 正常 session close。

写入流程：在同目录创建唯一临时文件，写 JSON、`force(true)`，再用 `Files.move(temp,target,ATOMIC_MOVE,REPLACE_EXISTING)`；文件系统不支持 `ATOMIC_MOVE` 时，在同目录用 `REPLACE_EXISTING` 并记录 warning。

读取时验证 session id、schema、`lastAppliedSequence` 和 event prefix hash。任一不符就忽略 checkpoint，从 event 0 replay；不能因为 checkpoint 损坏导致 session 不可恢复。

## 8. 旧格式迁移

`LegacySessionMigrator.migrate(Path rawFile)` 必须幂等：目标 session id 已存在且 manifest 记录相同 `legacySourceSha256` 时直接返回已有 session。

映射规则：

| v1 event | v2 映射 |
|---|---|
| `system` | `SYSTEM_MESSAGE append`，后续 system 使用 replace 当前 system 节点 |
| `user` | `USER_MESSAGE append` |
| `assistant` | `ASSISTANT_MESSAGE append` |
| `tool_call` | 带 toolCalls 的 `ASSISTANT_MESSAGE append`；另生成 `TOOL_CALL surface=none` |
| `tool_result` | `TOOL_RESULT append` |
| `history_clear` | `SURFACE_CLEAR` |
| `compaction` | `LEGACY_EVENT ignorable=true`；无法恢复旧压缩后的实际 surface 时将会话标为 `resumeUnsafe=true` |
| 其他事件 | `LEGACY_EVENT ignorable=true`，payload 保留原始 Entry |

旧日志没有可靠 request/turn 边界，迁移后不恢复 usage anchor。若存在旧 `compaction` 但没有压缩后消息内容，不得猜测 active surface；该 session 只允许 export，并在 `/sessions` 显示 `不可安全恢复`。

## 9. 多 Agent 会话

- 主 ReAct Agent 创建 root session。
- Plan 的每个执行 task 创建 child session，manifest 保存 `parentSessionId`、`mode=plan`、`actor=task-id`。
- Team 的每个 SubAgent 创建 child session，保存 `parentSessionId`、角色名和 delegation depth。
- child 完成后，父 session 追加一条 `USER_MESSAGE(source=child_result)` 或专用 ignorable metadata + 普通可见消息，payload 保存 child session id 和最终结果。
- 不再把同一个 `ConversationLedger` 实例设置给多个可并行写入的 Agent。
- child 异常退出可独立 resume；父 session 不自动把 child 的完整 history 合并进自己的 surface。

## 10. 分任务实施

### Task 1：定义事件协议和纯 replay

**文件：**

- 新建 `src/main/java/com/codeagent/history/SessionEvent.java`
- 新建 `src/main/java/com/codeagent/history/SessionManifest.java`
- 新建 `src/main/java/com/codeagent/history/SessionProjection.java`
- 新建 `src/main/java/com/codeagent/history/SessionReplayer.java`
- 新建 `src/test/java/com/codeagent/history/SessionReplayerTest.java`

**测试必须覆盖：** append 顺序、system 单节点替换、clear、范围替换、未闭合 compaction 不生效、completed compaction 生效、sequence gap、跨 session event、未知 required event、未完成 request 和 pending tool。

- [ ] 先写上述测试并运行：

```bash
mvn test -Dtest=SessionReplayerTest -DskipTests=false
```

预期：编译失败，缺少新类型。

- [ ] 实现事件类型、manifest、projection 和纯 replay。
- [ ] 再运行同一命令，预期全部 PASS。
- [ ] 提交：

```bash
git add src/main/java/com/codeagent/history src/test/java/com/codeagent/history/SessionReplayerTest.java
git commit -m "feat: add durable session event projection"
```

### Task 2：实现 SessionStore、文件锁和安全追加

**文件：**

- 新建 `src/main/java/com/codeagent/history/SessionStore.java`
- 新建 `src/main/java/com/codeagent/history/SessionSummary.java`
- 新建 `src/main/java/com/codeagent/history/SessionResumeResult.java`
- 新建 `src/test/java/com/codeagent/history/SessionStoreTest.java`

**测试必须覆盖：** 创建目录和 manifest、append 后可 replay、sequence 连续、重新打开继续 sequence、最后一行损坏、中间行损坏、两个 writer 锁冲突、manifest 原子更新、workspace 不匹配拒绝、正常 close。

- [ ] 写失败测试并运行：

```bash
mvn test -Dtest=SessionStoreTest -DskipTests=false
```

- [ ] 实现 `SessionStore`/`SessionHandle`，确保 append durable-first。
- [ ] 运行 `SessionStoreTest,SessionReplayerTest`，预期全部 PASS。
- [ ] 提交：

```bash
git add src/main/java/com/codeagent/history src/test/java/com/codeagent/history
git commit -m "feat: persist resumable session logs"
```

### Task 3：接入 ReAct Agent 消息与请求生命周期

**文件：**

- 修改 `src/main/java/com/codeagent/agent/Agent.java`
- 修改 `src/main/java/com/codeagent/context/InvalidationReason.java`
- 修改 `src/main/java/com/codeagent/history/ConversationLedger.java`
- 修改 `src/test/java/com/codeagent/agent/AgentConversationLedgerTest.java`
- 新建 `src/test/java/com/codeagent/agent/AgentSessionResumeTest.java`

**测试必须覆盖：** 新 session 只写一次 system、恢复不重复 system、user/assistant/tool surface 与退出前一致、落盘失败不调用 LLM、请求失败形成 incomplete/failed 状态、恢复后 tracker 为 `FULL_ESTIMATE`、新成功调用后重新使用 usage anchor。

- [ ] 写测试并确认失败。
- [ ] 增加构造注入和 `restoreProjection`，把消息追加改成 durable-first。
- [ ] 在每次实际 `chat()` 周围写 request lifecycle 和 usage event。
- [ ] 运行：

```bash
mvn test -Dtest=AgentConversationLedgerTest,AgentSessionResumeTest,ContextTokenTrackerTest -DskipTests=false
```

预期全部 PASS。

- [ ] 提交：

```bash
git add src/main/java/com/codeagent/agent/Agent.java src/main/java/com/codeagent/context/InvalidationReason.java src/main/java/com/codeagent/history/ConversationLedger.java src/test/java/com/codeagent/agent
git commit -m "feat: restore react context from session events"
```

### Task 4：持久化 `/clear`、图片裁剪和 compaction

**文件：**

- 修改 `src/main/java/com/codeagent/agent/Agent.java`
- 修改 `src/main/java/com/codeagent/memory/AutoCompactionManager.java`
- 修改 `src/main/java/com/codeagent/memory/ConversationHistoryCompactor.java`
- 修改 `src/test/java/com/codeagent/agent/AgentClearHistoryTest.java`
- 新建 `src/test/java/com/codeagent/history/SessionCompactionRecoveryTest.java`
- 修改相关 compactor 测试

**测试必须覆盖：** clear 后重启只剩新 system；原始事件仍在；完整 compaction 恢复摘要+tail；start/summary/replace 后崩溃仍恢复旧 surface；no-benefit 不替换；图片裁剪可恢复；overflow recovery 只有 surface generation 改变才重试。

- [ ] 先写失败测试。
- [ ] 将 compactor 改为计算 outcome、不直接提交；由 Agent 写完整 compaction lifecycle。
- [ ] 将 clear、system refresh、image prune 改成显式 surface 事件。
- [ ] 运行：

```bash
mvn test -Dtest=AgentClearHistoryTest,SessionCompactionRecoveryTest,ConversationHistoryCompactorTest,AutoCompactionManagerTest -DskipTests=false
```

- [ ] 提交：

```bash
git add src/main/java/com/codeagent/agent/Agent.java src/main/java/com/codeagent/memory src/test/java/com/codeagent
git commit -m "feat: persist context surface transformations"
```

### Task 5：实现 CLI session 生命周期和命令

**文件：**

- 修改 `src/main/java/com/codeagent/cli/CliCommandParser.java`
- 修改 `src/main/java/com/codeagent/cli/CodeAgentCompleter.java`
- 修改 `src/main/java/com/codeagent/cli/Main.java`
- 修改 `src/test/java/com/codeagent/cli/CliCommandParserTest.java`
- 修改 `src/test/java/com/codeagent/cli/CodeAgentCompleterTest.java`
- 新建 `src/test/java/com/codeagent/cli/MainSessionCommandTest.java`

**测试必须覆盖：** 三个新命令解析、session id 补全、auto 只恢复同 workspace 未关闭 session、`off` 新建、resume prepare 失败保持当前 Agent、`/new` 关闭旧 session、正常退出 close、异常 session 启动提示。

- [ ] 写失败测试。
- [ ] 实现 parser、completer 和 Main 的 prepare-then-swap。
- [ ] 运行：

```bash
mvn test -Dtest=CliCommandParserTest,CodeAgentCompleterTest,MainSessionCommandTest -DskipTests=false
```

- [ ] 提交：

```bash
git add src/main/java/com/codeagent/cli src/test/java/com/codeagent/cli
git commit -m "feat: add session resume commands"
```

### Task 6：checkpoint 和旧 ledger 迁移

**文件：**

- 新建 `src/main/java/com/codeagent/history/SessionCheckpointStore.java`
- 新建 `src/main/java/com/codeagent/history/LegacySessionMigrator.java`
- 新建 `src/test/java/com/codeagent/history/SessionCheckpointStoreTest.java`
- 新建 `src/test/java/com/codeagent/history/LegacySessionMigratorTest.java`
- 修改 `src/main/java/com/codeagent/history/SessionStore.java`

**测试必须覆盖：** checkpoint+tail 等于 full replay；损坏/hash 不符时 full replay；compaction 后立即 checkpoint；旧消息映射；clear 映射；旧 compaction 标记 unsafe；重复迁移不重复事件；旧文件不删除。

- [ ] 写失败测试。
- [ ] 实现 checkpoint 原子写/校验和 legacy 幂等迁移。
- [ ] 运行：

```bash
mvn test -Dtest=SessionCheckpointStoreTest,LegacySessionMigratorTest,SessionStoreTest -DskipTests=false
```

- [ ] 提交：

```bash
git add src/main/java/com/codeagent/history src/test/java/com/codeagent/history
git commit -m "feat: add session checkpoints and legacy migration"
```

### Task 7：Plan/Team 子会话隔离

**文件：**

- 修改 `src/main/java/com/codeagent/agent/PlanExecuteAgent.java`
- 修改 `src/main/java/com/codeagent/agent/SubAgent.java`
- 修改 `src/main/java/com/codeagent/agent/AgentOrchestrator.java`
- 修改 `src/main/java/com/codeagent/cli/Main.java`
- 修改 `src/test/java/com/codeagent/agent/PlanExecuteAgentTest.java`
- 修改 `src/test/java/com/codeagent/agent/SubAgentTest.java`
- 修改 `src/test/java/com/codeagent/agent/AgentOrchestratorTest.java`

**测试必须覆盖：** parent/child id 和 lineage、并行 SubAgent 不共享 writer、child 结果通过显式父事件注入、child 失败不损坏父 surface、独立恢复 child 不合并历史。

- [ ] 写失败测试。
- [ ] 用 `SessionStore.createChild(parentId, mode, actor)` 替代共享 ledger setter。
- [ ] 运行：

```bash
mvn test -Dtest=PlanExecuteAgentTest,SubAgentTest,AgentOrchestratorTest -DskipTests=false
```

- [ ] 提交：

```bash
git add src/main/java/com/codeagent/agent src/main/java/com/codeagent/cli/Main.java src/test/java/com/codeagent/agent
git commit -m "feat: isolate child agent sessions"
```

### Task 8：文档、安全检查和全量验收

**文件：**

- 修改 `AGENTS.md`
- 修改 `README.md`
- 修改 `docs/dev/06-memory-context.md`
- 修改 `docs/dev/07-hybrid-token-estimation.md`
- 更新本文件中的实际类名/命令（若实施中有经审查的变更）

**文档必须说明：** session 默认恢复策略、`/sessions`/`/resume`/`/new`、日志敏感性、`/clear` 不删除原始事件、重启后第一次 full estimate、未完成工具不自动执行、旧格式迁移限制。

**实现状态：** 上述功能已完成；Task 1–7 的代码、测试和提交均已交付。以下复选框是原始执行清单，不代表仍有待实现工作。

- [ ] 运行定向核心回归：

```bash
mvn test -Dtest=SessionReplayerTest,SessionStoreTest,AgentSessionResumeTest,SessionCompactionRecoveryTest,MainSessionCommandTest,ContextTokenTrackerTest -DskipTests=false
```

- [ ] 运行常规回归：

```bash
mvn test -Pquick
```

- [ ] 运行全量回归：

```bash
mvn test -DskipTests=false
```

- [ ] 手工崩溃验收：启动 CLI，完成一轮带只读工具的对话，在下一轮请求过程中终止进程；重新启动后确认自动恢复已提交 surface、标记中断请求、不重新执行工具。
- [ ] 手工压缩验收：构造长对话执行 `/compact`，重启后 `/export` 能看到原始事件，模型发送视图为摘要+tail。
- [ ] 检查仓库没有 session 数据、附件、`.env` 或 API key：

```bash
git status --short
git diff --check
git ls-files .env target "*.jsonl"
```

- [ ] 最终提交：

```bash
git add AGENTS.md README.md docs/dev/06-memory-context.md docs/dev/07-hybrid-token-estimation.md docs/dev/10-persistent-session-implementation-plan.md
git commit -m "docs: document persistent session recovery"
```

## 11. 发布门槛

**本次验收记录（2026-09-16）：** 持久化 session 定向测试已通过（67 tests, 0 failures）；全量 Maven 回归已执行，但受 Windows 路径/换行差异、SQLite readonly 测试环境和既有工具/RAG 基线问题影响，结果为 962 tests、13 failures、5 errors、8 skipped。因此本分支不能宣称“全量测试通过”；这些失败不涉及新增 session 测试，后续应在独立基线修复后重跑发布门槛。

以下条件全部满足才可宣称“短期记忆已持久化”：

- 同一 session 在正常退出、异常终止和重启后得到相同 committed active surface。
- replay 结果只依赖 event log/checkpoint，不依赖旧进程内对象。
- `/clear` 和 compaction 的原始事件仍可审计，发送视图符合其 surface 语义。
- 未完成工具调用不会在恢复时自动执行。
- checkpoint 损坏不会导致历史丢失。
- 旧格式无法安全恢复时明确只读，不能猜测上下文。
- 恢复后首轮为 `FULL_ESTIMATE`；取得新可信 usage 后才回到 `USAGE_ANCHORED_DELTA`。
- ReAct、Plan、Team 的写入 session 隔离，父子关系可追踪。
- `mvn test -Pquick` 与 `mvn test -DskipTests=false` 均通过。

## 12. 明确不在本次范围内

- session 内容全文搜索和云同步。
- 多进程协同写同一个 session。
- 自动重放未完成的写文件、命令、浏览器或 MCP 副作用。
- 对日志内容做透明加密；第一版沿用本机权限隔离，后续可单独设计 OS keychain 加密。
- 从旧 `compaction` 元数据反推出已丢失的摘要文本。
- 跨 workspace 静默恢复；跨项目恢复必须由后续显式导入/确认功能处理。
\n> Implementation status: Tasks 1-7 are now implemented in the persistent-sessions worktree. The remaining acceptance work is regression verification and documentation maintenance; do not interpret the historical "not implemented" boundary text below as current code state.
