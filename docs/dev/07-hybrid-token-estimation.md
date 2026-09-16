# 混合 Token 计量与上下文压缩实现计划

> **给实现者：** 本文是可直接执行的实现规格。实现顺序按任务拆分；每个任务都给出文件、接口、测试和验收条件。实现时使用 `subagent-driven-development`（推荐）或 `executing-plans`，逐项勾选，不跨过失败测试。外部调研依据见 [08-harness-token-context-survey.md](08-harness-token-context-survey.md)，重点参考 DeepSeek Harness 的 `token-meter` 与 `compaction-basic`。

**目标：** 将 CodeAgent 的压缩判断从“只估算 `conversationHistory`”升级为“完整请求计量 + provider usage 锚点 + surface 有符号增量 + 安全回退 + 超限恢复”，并保持长期记忆、账本和活动对话的现有语义。

**架构：** `ContextTokenTracker` 不拥有或复制短期消息，只保存最近一次成功请求的测量锚点；`TokenBudget` 提供确定性的完整请求启发式计量；`Agent` 在实际请求 envelope 冻结后调用 tracker；`AutoCompactionManager` 只负责压缩执行，压缩后使 tracker 失效并重新测量。system prompt 是 surface 节点，长期记忆 top-k 改变只产生 surface delta，不因对象替换自动回退。

**技术栈：** Java 17、现有 `LlmClient`/Provider 客户端、Jackson、JUnit 5、现有 `ConversationHistoryCompactor`、`SessionMemoryCompactor` 和 `ConversationLedger`。

## 全局约束

- 以代码实际行为为准；不得把 `ROADMAP.md` 的规划当成已实现功能。
- `conversationHistory` 是唯一短期上下文，不新增 `MemoryManager.shortTermMemory` 或其他影子消息副本。
- `ConversationLedger` 仍为 append-only 原始账本；压缩只改变发送视图并追加边界事件，不改写旧消息。
- 长期记忆仍只通过 `/save`、`save_memory` 或用户明确要求写入；本计划不增加自动事实提取。
- provider usage 只在语义已确认且请求 envelope 可比时作为锚点；不能把 output 或 cached input 无条件当作下一次 input。
- 所有估算都必须包含 system、tools、消息角色/分隔开销、tool call/result、图片和其他模型可见块。
- 任意压缩、`/clear`、模型/provider/call-config/tools 改变、图片 payload 裁剪、消息重排后，旧锚点必须失效或进入完整估算路径。
- 默认自动压缩阈值继续由 `ContextProfile.compressionTriggerTokens()` 提供；当前大窗口公式为窗口减摘要输出预留和安全缓冲。
- 默认不增加固定 ReAct 迭代上限；压缩失败不能丢弃已有工作，必须继续或返回部分完成结果。

## 1. 已有实现与改造边界

### 1.1 当前调用顺序

当前 `Agent.run` 的关键顺序如下：

```text
pruneHistoricalImagePayloads()
→ buildContextForQuery() / updateSystemPromptWithMemory()
→ append user message
→ loop:
   injectPendingLspDiagnostics()
   maybeCompactHistory()                 // 只估算 history
   budget.check()
   getToolDefinitions() / TurnToolPolicy.expose()
   llmClient.chat(conversationHistory, tools)
   budget.recordTokens(response usage)
   append assistant/tool messages
```

相关代码：

- `src/main/java/com/codeagent/agent/Agent.java:160-225,436-452`
- `src/main/java/com/codeagent/memory/AutoCompactionManager.java`
- `src/main/java/com/codeagent/memory/TokenBudget.java:48-134`
- `src/main/java/com/codeagent/agent/AgentBudget.java`
- `src/main/java/com/codeagent/llm/LlmClient.java:1-30,204-220`

### 1.2 当前问题

1. `TokenBudget.estimateMessagesTokens` 不知道工具 schema，`Agent.estimateCurrentContextTokens` 另行估算 tools，二者不能作为同一个请求快照复用。
2. `maybeCompactHistory()` 在工具定义最终暴露前执行，无法保证判断值对应真正发送的 envelope。
3. 每次 system prompt 由长期记忆 top-k 替换时，当前代码只看到 `conversationHistory[0]` 被 set，缺少内容级 surface delta。
4. `ChatResponse.inputTokens/outputTokens/cachedInputTokens` 只进入 `AgentBudget` 累计统计，没有保存“这次 usage 对应的请求快照”。
5. provider context overflow 没有统一的“压缩产生真实变化后再 retry”契约。

### 1.3 文件改动地图

实现时按下表建立变更清单。新增类先写测试再写实现；修改现有类时保持旧 API 的兼容包装，避免一次改动扩散到三条 Agent 路径。

| 操作 | 文件 | 职责 / 约束 |
|---|---|---|
| 新增 | `src/main/java/com/codeagent/context/RequestSnapshot.java` | 保存一次实际请求的 envelope、surface 摘要、估算值和 `historyVersion`；不可持有 `List<Message>` 引用 |
| 新增 | `src/main/java/com/codeagent/context/MeasuredUsage.java` | 归一化 provider input/output/cache、input scope 和可信度；未知语义必须 `trusted=false` |
| 新增 | `src/main/java/com/codeagent/context/InvalidationReason.java` | 统一记录锚点失效原因 |
| 新增 | `src/main/java/com/codeagent/context/ContextTokenTracker.java` | 保存最近一次成功调用的锚点和误差样本，执行完整估算/有符号 delta 预测；不修改 history |
| 新增 | `src/main/java/com/codeagent/context/RequestSnapshotFactory.java` | 对最终 `messages + tools` 生成不可变快照；必须在 `chat()` 前调用 |
| 新增 | `src/main/java/com/codeagent/memory/TokenEstimationException.java` | 未知或无法安全定价的模型可见内容的明确异常 |
| 新增 | `src/main/java/com/codeagent/llm/ContextWindowExceededException.java` | 各 provider 已确认的上下文超限错误的统一类型 |
| 修改 | `src/main/java/com/codeagent/memory/TokenBudget.java` | 增加完整 request estimator，保留旧消息估算 API |
| 修改 | `src/main/java/com/codeagent/agent/Agent.java` | 冻结工具暴露、捕获 snapshot、预测/压缩、记录 usage、执行 overflow recovery |
| 修改 | `src/main/java/com/codeagent/memory/AutoCompactionManager.java` | 只执行裁剪/摘要，不再自行维护第二套 pressure 估算 |
| 修改 | `src/main/java/com/codeagent/memory/ConversationHistoryCompactor.java` | 保证 tool-call/result 配对、摘要结果严格变短 |
| 修改 | `src/main/java/com/codeagent/llm/LlmClient.java` | 增加 `normalizeUsage()` 和 `requestConfigurationFingerprint()` 默认契约 |
| 修改 | `src/main/java/com/codeagent/llm/*Client.java` | 仅在 provider 语义已由文档/测试确认时覆盖 usage 归一化，否则沿用不可信默认值 |
| 修改 | `src/main/java/com/codeagent/render/StatusInfo.java`、`Main.java` | 分离展示 `ctx projected` 与最近一次 measured usage；不把二者混为同一数字 |
| 新增 | `src/test/java/com/codeagent/context/*Test.java`、`src/test/java/com/codeagent/agent/*Context*Test.java`、`OverflowRecoveryTest.java` | 覆盖 estimator、锚点边界、压缩和超限恢复；详见任务 A-F |

除上述文件外，若 Plan、Team、SubAgent 复制了 Agent 的请求前逻辑，必须复用同一个 snapshot/tracker 服务；禁止在分支内重新实现一套 token 公式。

## 2. 术语、口径和不变量

### 2.1 四种 token 口径

| 名称 | 定义 | 允许用途 |
|---|---|---|
| `estimatedRequestTokens` | 本地启发式对下一次完整请求的预测：system + tools + surface + 多模态块 | 压缩触发前预测、`ctx` 状态栏、误差校准 |
| `measuredInputTokens` | provider 报告的本次 prompt/input token | 建立锚点；只有语义可信才参与预测 |
| `measuredOutputTokens` | provider 报告的本次输出 token（通常已包含 assistant 文本、reasoning 和 tool call） | 计费、任务统计；只有确认与本次 assistant message 一一对应时，计入一次 usage 锚点；不得再叠加独立的 tool-call token |
| `measuredCachedInputTokens` | provider 报告的缓存读/写 token | 成本和缓存展示；是否计入 pressure 由 provider 语义决定 |

`MemoryEntry.tokenCount` 仍是单条长期记忆的本地估算，不能当作请求 input token。`AgentBudget` 的累计 usage 仍保留，tracker 不取代它。

### 2.2 请求 envelope 与 surface

`RequestSnapshot` 分成两部分：

- **Envelope（结构性请求输入）**：provider、model、reasoning/sampling/output 参数、工具 schema 的规范化内容、工具暴露策略、图片路由能力。
- **Surface（会话可见内容）**：system prompt、user、assistant reasoning/content、tool call、tool result、LSP/MCP/skill 注入、图片/文件模型可见内容。

不变量：

```text
同一个 snapshot 的 estimatedRequestTokens
    = estimate(envelope.tools) + estimate(surface)

若只追加或替换可计价 surface，且 envelope 不变：
    currentEstimate - anchorEstimate 是有符号 surface delta

若 envelope 改变或 surface 无法对齐：
    不得使用 provider usage 锚点，必须完整估算 current snapshot
```

## 3. 数据结构设计

### 3.1 `RequestSnapshot`

新增文件：`src/main/java/com/codeagent/context/RequestSnapshot.java`。

```java
public record RequestSnapshot(
        String provider,
        String model,
        String callConfigFingerprint,
        String toolSchemaFingerprint,
        String surfaceFingerprint,
        String systemPromptFingerprint,
        int estimatedSurfaceTokens,
        int estimatedToolTokens,
        int messageCount,
        int imageCount,
        long historyVersion,
        boolean containsUnsupportedPart) {

    public int estimatedRequestTokens() {
        return Math.max(0, estimatedSurfaceTokens + estimatedToolTokens);
    }
}
```

指纹规则：

1. `callConfigFingerprint` 对 provider、model、reasoning effort、temperature/top-p、max output 等实际发送字段做稳定 JSON 序列化后 SHA-256；字段顺序固定。
2. `toolSchemaFingerprint` 对最终 `TurnToolPolicy.expose()` 返回的工具列表按发送顺序序列化后 SHA-256；`null`、空列表和字段缺失要规范化为同一 canonical 形式。
3. `systemPromptFingerprint` 对实际发送的 system 文本做 Unicode NFKC、换行符归一化和 UTF-8 SHA-256；不得只比较 `Message` 对象身份。
4. `surfaceFingerprint` 对消息 role、content-part 类型、tool-call id/name/arguments、tool-result 关联 id 和顺序做摘要指纹；图片 payload 使用内容 hash + 媒体类型 + 尺寸，不把完整 base64 存入 tracker。
5. 指纹只用于判断可比性和诊断，不写入长期记忆，也不改变账本内容。

### 3.2 `MeasuredUsage`

新增文件：`src/main/java/com/codeagent/context/MeasuredUsage.java`。

```java
public record MeasuredUsage(
        int inputTokens,
        int outputTokens,
        int cachedInputTokens,
        InputScope inputScope,
        boolean includesTools,
        boolean includesSystem,
        boolean trusted,
        Instant measuredAt) {

    public enum InputScope {
        TOTAL_PROMPT,
        UNCACHED_PROMPT,
        UNKNOWN
    }

    public long promptPressureTokens() {
        if (inputScope == InputScope.TOTAL_PROMPT) return Math.max(0, inputTokens);
        if (inputScope == InputScope.UNCACHED_PROMPT) {
            return Math.max(0L, (long) inputTokens + cachedInputTokens);
        }
        return 0L;
    }

    public long usageAnchorTokens() {
        return promptPressureTokens() + Math.max(0, outputTokens);
    }
}
```

实现要求：

- provider 未确认字段语义时使用 `InputScope.UNKNOWN`、`trusted=false`；tracker 必须完整估算。
- 若 provider 的 `inputTokens` 已含 cached input，不得再次相加；DeepSeek Harness 的 `input + cacheRead + cacheWrite + output` 只能作为其自身 provider 语义的示例，不能直接套给所有客户端。
- 负数、非有限值、明显矛盾的 total/bucket 组合一律 `trusted=false`。

### 3.3 `ContextAnchor`、`ContextPrediction`、`ContextTokenTracker`

新增文件：`src/main/java/com/codeagent/context/ContextTokenTracker.java`。

```java
public final class ContextTokenTracker {
    public record ContextAnchor(
            RequestSnapshot snapshot,
            long anchorSurfaceTokens,
            MeasuredUsage usage,
            boolean usageTrusted,
            Instant measuredAt) {}

    public enum Mode { NONE, FULL_ESTIMATE, USAGE_ANCHORED_DELTA }

    public record ContextPrediction(
            long rawPredictedTokens,
            long effectiveTokens,
            long fullEstimateTokens,
            long surfaceDeltaTokens,
            long safetyMarginTokens,
            Mode mode,
            boolean safeForCompaction,
            String fallbackReason) {}

    public ContextPrediction predict(RequestSnapshot current);
    public void recordSuccessfulCall(
            RequestSnapshot request,
            int assistantSurfaceEstimateTokens,
            MeasuredUsage usage);
    public void invalidate(InvalidationReason reason);
    public boolean hasUsableAnchor(RequestSnapshot current);
}
```

tracker 只保存单个最近成功锚点及误差样本，不保存 `List<Message>`。`ContextTokenTracker` 不负责调用 LLM、不执行压缩、不修改 `conversationHistory`。

字段语义必须固定：`fullEstimateTokens` 是当前请求的本地原始估算；`rawPredictedTokens` 是使用 provider 锚点和 surface delta 后、尚未加误差余量的预测；`safetyMarginTokens` 是按 provider/model 校准得到的非负余量；`effectiveTokens = rawPredictedTokens + safetyMarginTokens`（使用饱和加法，溢出时取 `Long.MAX_VALUE`）。压缩阈值只比较 `effectiveTokens`。`ContextProfile` 已包含摘要输出预留和 13k 安全缓冲，不能把该缓冲再次填入 `safetyMarginTokens`。

## 4. 完整请求估算器

### 4.1 `TokenBudget` API 改造

在 `src/main/java/com/codeagent/memory/TokenBudget.java` 保留旧方法兼容已有测试，新增：

```java
public static int estimateRequestTokens(
        List<LlmClient.Message> messages,
        List<LlmClient.Tool> tools);

public static int estimateMessagesTokens(List<LlmClient.Message> messages);
public static int estimateMessageTokens(LlmClient.Message message);
public static int estimateToolsTokens(List<LlmClient.Tool> tools);
```

实现规则：

1. `estimateRequestTokens` 返回 `estimateMessagesTokens(messages) + estimateToolsTokens(tools)`。
2. 每条消息计入 role/separator 固定开销；文本、reasoning、tool-call arguments、tool-result 嵌套内容递归计量。
3. 图片使用现有 base64 尺寸启发式；无法识别的 part 按其 JSON 结构做保守估算，不能静默计为 0。
4. 工具 schema 使用 Jackson 稳定序列化；序列化失败必须返回明确的保守上界或抛出异常，不得返回“成功但为 0”。
5. 不把 `cachedInputTokens` 加入本地估算；它只在 `MeasuredUsage` 归一化阶段处理。
6. 无法序列化未知模型可见块时抛出 `TokenEstimationException`；`Agent` 捕获后以当前 `compressionTriggerTokens()` 作为本轮保守有效值，尝试压缩，并保留 overflow recovery 兜底。

建议按下面的固定顺序实现，确保不同调用方得到同一结果：

```text
estimateMessage(m):
  total = ROLE_OVERHEAD + estimate(m.role) + SEPARATOR_OVERHEAD
  total += estimate(m.reasoningContent)
  if m.contentParts is non-empty:
      for part in m.contentParts:
          total += PART_OVERHEAD + estimatePart(part)
  else:
      total += estimate(m.content)
  for call in m.toolCalls:       // 本地结构估算；不是 provider usage 的额外 bucket
      total += TOOL_CALL_OVERHEAD
      total += estimate(call.id) + estimate(call.function.name)
      total += estimateJsonOrText(call.function.arguments)
  total += estimate(m.toolCallId)
  return saturatingInt(total)

estimateTools(tools):
  canonical = stableJson(tools)       // 保留列表顺序
  return saturatingInt(TOOLS_OVERHEAD + estimate(canonical))

estimateRequest(messages, tools):
  return saturatingInt(sum(estimateMessage(m)) + estimateTools(tools))
```

`estimatePart` 对 `text` 使用 `MemoryEntry.estimateTokens`；对 `image_base64` 使用现有图片尺寸启发式；对 `image_url` 至少计入 URL、媒体类型和图片块固定开销；对未知 `type` 将完整 part 以稳定 JSON 估算，序列化失败抛出 `TokenEstimationException`。所有累加先用 `long`，超过 `Integer.MAX_VALUE` 时饱和为该值并在快照中标记 `containsUnsupportedPart`/`fallbackReason`，不得整数溢出为负数。

### 4.2 核心预测公式

对于当前请求快照 `S` 和上一成功请求锚点 `A`：

```text
fullEstimate(S) = estimateTools(S) + estimateSurface(S)

if sameCanonicalEnvelope(A, S)
   && A.usageTrusted
   && A.usage.includesSystem
   && A.usage.includesTools
   && A.usage.usageAnchorTokens >= A.anchorSurfaceTokens + estimateTools(A):
     rawPredicted(S) = A.usage.usageAnchorTokens
                       + (estimateSurface(S) - A.anchorSurfaceTokens)
     mode = USAGE_ANCHORED_DELTA
else:
     rawPredicted(S) = fullEstimate(S)
     mode = FULL_ESTIMATE

safetyMargin(S) = calibration.margin(A/S provider + model + call config)
effective(S) = saturatingAdd(max(0, rawPredicted(S)), safetyMargin(S))
```

`surfaceDeltaTokens` 可以为正或负。长期记忆 top-k 替换 system prompt 时，若 envelope 未变，直接将新旧 system 节点估算差计入 delta；不得因为 `conversationHistory.set(0, ...)` 就无条件清除 usage 锚点。

`sameCanonicalEnvelope(A, S)` 只比较以下规范化字段：provider、model、实际发送的 reasoning/sampling/output 配置、工具 schema（名称、描述、parameters 和顺序）、工具暴露策略版本以及图片/多模态路由能力。它**不**比较 `surfaceFingerprint`、`systemPromptFingerprint`、`historyVersion` 或 Java 对象身份；这些属于 surface 可变部分或本地版本元数据。规范化后使用稳定 JSON + SHA-256 比较，字段缺失、序列化失败或策略版本未知时返回“不相同”。

预测实现必须把安全余量显式分层：

```text
rawPredicted = anchor-or-full-estimate result
margin       = calibration.margin(provider, model, requestConfiguration)
effective    = saturatingAdd(rawPredicted, max(0, margin))
```

这样可以审计“provider 锚点/内容增量”和“本地误差防护”各自贡献，避免把 13k 压缩缓冲、cached input 或单条 `MemoryEntry.tokenCount` 重复加进上下文。

### 4.3 端到端数值例子

假设上一请求发送前，消息 surface（system + history）估算为 `7,200`，工具定义 schema 估算为 `2,000`；provider 返回可信 `input=9,200`、`output=800`。这里的 `output=800` 已包含本次 assistant 文本、reasoning 和 tool call；工具定义不是 output，而是请求发出前随 `tools` 参数发送的输入。因此：

```text
usageAnchor   = 9,200 + 800 = 10,000
anchorSurface = 7,200 + 800 = 8,000
```

下一请求因新增 user 轮次和新的长期记忆 system 节点，当前消息 surface 为 `8,450`，工具 schema 未变：

```text
surfaceDelta       = 8,450 - 8,000 = +450
rawPredicted       = 10,000 + 450 = 10,450
fullEstimate       = 8,450 + 2,000 = 10,450   // 诊断对照，不重复增加 tool call
safetyMargin       = 256
effectiveTokens    = 10,450 + 256 = 10,706
```

若只是 system prompt top-k 由 300 token 换成 180 token，则 delta 中对应部分为 `-120`；不清除锚点，也不把上一轮 output 或 tool call 再加一次。若工具 schema 新增字段，`sameCanonicalEnvelope=false`，直接使用新的 `fullEstimate` 并等待下一次成功 usage 建立锚点。

### 4.4 assistant 输出边界

provider input usage 对应请求发出前的 prompt，而下一次请求会把本次 assistant 输出以及 tool result 放进 history。`recordSuccessfulCall` 必须保存：

```text
anchorSurfaceTokens = 本次请求发送前的 surface 估算
                    + 本次 assistant message 的本地结构估算
```

若 provider output token 已用于 `usageAnchorTokens`，surface delta 的 anchor 也必须包含对应的整个 assistant message（包括 tool call）；这两处是同一份内容的两个坐标表示，不能把本地 assistant/tool-call 估算再次加到 provider usage 上。必须以单元测试固定这一边界。

## 5. 锚点生命周期和失效规则

### 5.1 创建锚点

在 `llmClient.chat(...)` 成功返回后执行：

```text
读取发送前 RequestSnapshot
→ 归一化 input/output/cached usage
→ 估算本次 assistant message
→ tracker.recordSuccessfulCall(request, assistantSurfaceEstimateTokens, usage)
→ 按现有逻辑 append assistant/tool messages
```

调用取消、provider 抛错、SSE 不完整或 usage 不可信时，不创建可复用锚点。

### 5.2 可走 signed surface delta 的变化

- 新增 user prompt；
- 锚点建立后新增的 user/tool-result/LSP/MCP/Skill 内容，以及 system prompt 的可计价替换；正常情况下 assistant reasoning/content/tool call 已包含在上一轮 output 和 anchorSurface 中，不作为独立 tool-call token 再加一次；
- 新增对应 tool result、LSP diagnostics、MCP/resource、skill 内容；
- system prompt 内容替换，包括长期记忆 top-k 变化；
- 可计价的 tool result 文本缩短，且未改变工具 schema 和消息顺序。

### 5.3 必须完整估算的变化

新增 `src/main/java/com/codeagent/context/InvalidationReason.java`：

```java
public enum InvalidationReason {
    INITIAL,
    PROVIDER_CHANGED,
    MODEL_CHANGED,
    CALL_CONFIG_CHANGED,
    TOOL_SCHEMA_CHANGED,
    COMPACTION,
    CLEAR,
    IMAGE_PAYLOAD_PRUNED,
    MESSAGE_REORDERED,
    MESSAGE_DELETED,
    UNSUPPORTED_CONTENT,
    USAGE_UNTRUSTED,
    OVERFLOW_RECOVERY
}
```

失效只清除 tracker 锚点，不清除 `conversationHistory` 或长期记忆。下一次请求完整估算；成功返回新 usage 后重建锚点。

### 5.4 持久化边界

- `RequestSnapshot`、当前 `ContextAnchor`、误差样本和 `historyVersion` 只保存在当前 Agent 进程内存中；第一阶段不写入 `ConversationLedger`、长期记忆或用户配置文件。
- 进程重启、会话重连或 provider 切换后没有可复用锚点，第一轮请求固定走 `FULL_ESTIMATE`；成功且 usage 可信后才建立新锚点。
- `ConversationLedger` 仍按现有 append-only 规则记录原始 system/user/assistant/tool 事件以及 compaction 边界事件；不得把 `RequestSnapshot` 的完整图片 payload、usage 伪造为对话消息或回写旧行。
- `AgentBudget` 的累计 input/output/cache 统计保持现有生命周期，不作为 tracker 的持久化替代品；状态栏可读取二者，但必须分别标注 measured 与 projected。

### 5.5 system prompt 的判断

不实现固定“变化超过 20%”规则，判断顺序为：

```text
provider/model/call config/tool schema canonical 相同？
    否 → FULL_ESTIMATE
    是 → surface 是否仍能按节点顺序和类型定价？
            否 → FULL_ESTIMATE
            是 → system/message 替换计入 signed delta
```

system prompt hash 不同只说明 delta 非零，不代表 envelope 改变。消息整体重排、无法定价的动态块或图片 payload 无法对齐时才完整估算。

## 6. Agent 请求边界改造

### 6.1 `RequestSnapshotFactory`

新增 `src/main/java/com/codeagent/context/RequestSnapshotFactory.java`：

```java
public final class RequestSnapshotFactory {
    public RequestSnapshot capture(
            LlmClient client,
            List<LlmClient.Message> messages,
            List<LlmClient.Tool> tools,
            long historyVersion);
}
```

工厂使用最终 `TurnToolPolicy.expose()` 返回的工具列表，读取实际 `conversationHistory`，不得修改或复制成另一份长期存活的消息历史。

### 6.2 `Agent.run` 的目标顺序

```text
injectPendingLspDiagnostics()
→ getToolDefinitions()
→ TurnToolPolicy.expose()
→ snapshotFactory.capture(actual messages, actual tools)
→ contextTokenTracker.predict(snapshot)
→ 达阈值时执行压缩
→ 若压缩成功：invalidate + 重新 capture/predict
→ budget.check()
→ llmClient.chat(snapshot 对应的 messages/tools)
→ normalizeUsage(response)
→ recordSuccessfulCall(snapshot, assistantSurfaceEstimateTokens, usage)
→ budget.recordTokens(...)
→ append assistant/tool messages
```

`toolExposure` 在 prediction 和 `chat` 之间不得改变；若策略层重新生成工具列表，必须重新 capture。

### 6.3 `historyVersion`

在 `Agent` 增加单调递增 `historyVersion`。以下操作后递增：

- append user/assistant/tool/LSP/MCP/skill message；
- 替换 system prompt；
- 删除历史图片 payload；
- 自动或手动压缩；
- `/clear`；
- tool-result prune。

替换 system prompt 只递增 version，不直接 invalidate；压缩、clear、图片裁剪和结构重排同时 invalidate。

### 6.4 `maybeCompactHistory` 新签名

```java
private boolean maybeCompactHistory(
        RequestSnapshot snapshot,
        ContextTokenTracker.ContextPrediction prediction,
        int triggerTokens) {
    if (prediction.effectiveTokens() < triggerTokens) return false;
    AutoCompactionManager.Result result = autoCompactionManager.compactIfNeeded(
            conversationHistory, triggerTokens, prediction.effectiveTokens());
    if (!result.compacted()) return false;
    historyVersion++;
    contextTokenTracker.invalidate(InvalidationReason.COMPACTION);
    recordCompaction("automatic:" + result.strategy().name().toLowerCase(),
            snapshot.messageCount(), prediction.effectiveTokens());
    return true;
}
```

`Agent` 负责统一预测与阈值，`AutoCompactionManager` 负责压缩执行；不得各自重新估算并形成两个触发口径。

### 7.1.1 阈值计算与比较顺序

每次请求只调用一次 `ContextProfile.compressionTriggerTokens()` 得到绝对阈值，并执行 `effectiveTokens >= triggerTokens` 比较。不要在 `Agent`、`TokenBudget` 或 `AutoCompactionManager` 中复制比例公式。

当前 `ContextProfile` 的派生规则是：

```text
safeWindow    = max(8_000, maxContextWindow)
summaryReserve = min(20_000, max(1_000, floor(safeWindow / 4)))
buffer         = min(13_000, max(1_000, floor(safeWindow / 8)))
trigger        = clamp(safeWindow - summaryReserve - buffer, 1_000, safeWindow - 1)
```

因此 200K/1M 窗口分别约为 `167K`/`967K`；小窗口按同一派生函数自动缩小预留，不得硬编码“大窗口”数值。`safetyMarginTokens` 是估算误差的额外保护，只加到 `effectiveTokens`，不改变 `ContextProfile` 的摘要输出预留。

## 7. AutoCompactionManager 与压缩器

### 7.1 API

增加：

```java
public Result compactIfNeeded(
        List<LlmClient.Message> history,
        int triggerTokens,
        long measuredOrPredictedTokens);
```

执行顺序：

1. `measuredOrPredictedTokens < triggerTokens` 返回 `NONE`。
2. Session Memory 开启且摘要 ready、未失效、能缩短 history 时优先采用。
3. Session Memory 不可用、为空、失效或收益不足时回退 `ConversationHistoryCompactor`。
4. 完整摘要按 user 轮次切分，保持 assistant tool call 与 tool result 配对。
5. 成功后由 `Agent` 使 tracker 失效并重新捕获；失败时原 history 不变。

旧两参数方法不参与正常预测；ReAct、Plan task 和 SubAgent 都先使用 snapshot/tracker。它仅在 Plan/SubAgent 无法生成快照、无法安全测量实际请求时作为明确的 legacy fallback，避免异常时丢弃已有工作；不得把它作为正常压缩入口。

### 7.2 tool-result 先裁剪

摘要前增加确定性裁剪：

- 只裁剪超出单结果预算的文本 tool result；
- 保留 call id、工具名、结果头尾和错误状态；
- 保持 tool call/result 配对；
- 裁剪后重新 capture，低于阈值则跳过摘要；
- 仍超阈值才执行 Session Memory/完整摘要。

### 7.3 压缩后语义

summary 不写入 `LongTermMemory`。压缩只原地重建活动 `conversationHistory` 并追加 ledger compaction 边界事件。压缩后下一次预测使用 `FULL_ESTIMATE`，直到新的 provider usage 成功返回。

## 8. Provider usage 归一化

### 8.1 `LlmClient` 默认实现

在 `LlmClient.java` 增加：

```java
default MeasuredUsage normalizeUsage(ChatResponse response) {
    return new MeasuredUsage(
            Math.max(0, response.inputTokens()),
            Math.max(0, response.outputTokens()),
            Math.max(0, response.cachedInputTokens()),
            MeasuredUsage.InputScope.UNKNOWN,
            false,
            false,
            false,
            Instant.now());
}

default String requestConfigurationFingerprint() {
    return getProviderName() + "\u0000" + getModelName();
}
```

默认不可信，防止把未知 provider 语义错误复用。客户端只有在 API 文档、解析代码和测试均确认后才覆盖并声明：

- input 是否包含缓存部分；
- input 是否包含 system、tools、多模态块；
- output 是否与 history 中的 assistant 内容一一对应；
- provider total token 是否可直接用作 context anchor。

动态 reasoning effort、temperature、top-p、max output 或其他请求参数存在时，对应客户端必须覆盖 `requestConfigurationFingerprint()`，返回这些**实际发送值**的稳定 SHA-256；快照工厂不得通过反射读取客户端私有字段。

### 8.2 Provider 文件

逐个修改并测试：

```text
src/main/java/com/codeagent/llm/GLMClient.java
src/main/java/com/codeagent/llm/DeepSeekClient.java
src/main/java/com/codeagent/llm/StepClient.java
src/main/java/com/codeagent/llm/KimiClient.java
src/main/java/com/codeagent/llm/FreeLlmApiClient.java
src/main/java/com/codeagent/llm/AgnesClient.java
src/main/java/com/codeagent/llm/XfyunMaaSClient.java
src/main/java/com/codeagent/llm/HunyuanClient.java
```

无法确认的客户端保持 `trusted=false`，走完整估算；不得用猜测填充语义。

### 8.3 当前验证状态（2026-09-15）

DeepSeek 已使用项目 `DeepSeekClient`、GLM 已使用项目 `GLMClient` 和真实 API 完成四组契约探针：短 system 基线、长 system、大 tools schema、强制 assistant tool call。重复运行观察到：

```text
baseline       prompt_tokens=39
long system    prompt_tokens=1536
large tools    prompt_tokens=2087
tool call      completion_tokens=62~70, hasToolCalls=true
cache sample   prompt_tokens=1536, prompt_cache_hit_tokens=1280
```

由此确认当前 DeepSeek Chat Completions 口径：

- `prompt_tokens` 覆盖 system 和 tools schema；
- `prompt_tokens` 是完整 prompt 总量，已包含 cached prefix，不能再加 `prompt_cache_hit_tokens`；
- `completion_tokens` 覆盖 reasoning 和 assistant tool call，不能再加独立 tool-call token；
- 非法或矛盾 usage（无 input、负数、cache 大于 prompt）不建立锚点。

因此 `DeepSeekClient.normalizeUsage()` 返回 `TOTAL_PROMPT`、`includesSystem=true`、`includesTools=true`、`trusted=true`，DeepSeek 正常下一轮可进入 `USAGE_ANCHORED_DELTA`。真实契约测试位于 `ProviderUsageLiveContractTest`，默认不进入 CI，显式使用 `-Dcodeagent.live.provider-usage=true` 启用，且不打印 key、prompt 或响应正文。

GLM 真实样本为 `baseline input=15`、`long system input=1512`、`large tools input=1945`、`tool call output=32` 且返回 tool call。GLM 的 `prompt_tokens` 覆盖 system/tools，`completion_tokens` 覆盖 assistant tool call；`GLMClient.normalizeUsage()` 现已按 `TOTAL_PROMPT`、`includesSystem=true`、`includesTools=true`、`trusted=true` 归一化，下一轮可进入 `USAGE_ANCHORED_DELTA`。本次样本未出现可报告的 GLM cached input 字段，因此没有对 GLM cache 语义做额外推断。

其他 provider 仍需分别完成真实契约验证后才能覆盖默认归一化。

## 9. Context overflow recovery

### 9.1 统一异常

新增 `src/main/java/com/codeagent/llm/ContextWindowExceededException.java`。各 provider 只把已确认的 context-length 错误归一化为该异常；认证、限流、普通 400、网关错误不得误分类。

### 9.2 Agent 恢复流程

```text
chat() 抛 ContextWindowExceededException
→ 不记录伪 usage
→ 最多 maxOverflowRetries（默认 1）
→ deterministic tool-result prune
→ 仍超限则执行 tool-pair-balanced summary compaction
→ 只有 historyVersion/surfaceGeneration 真正增加才 retry
→ 无变化、恢复失败或达到上限时返回原始 provider 错误
```

recovery 不要求已有 usage，也不要求正常 pressure 阈值已达到。产生变化后 tracker 失效，重试必须重新构造 tools、snapshot 和预测值。

## 10. 状态栏、`/context` 与导出

展示字段分离：

```text
ctx projected : 下一请求预测值
ctx mode      : USAGE_ANCHORED_DELTA / FULL_ESTIMATE / NONE
ctx measured  : 最近一次 provider input/output/cache usage
ctx window    : 当前 provider/model context window
```

`ctx projected` 用于压缩和占用显示；`in/out/cache` 仍是任务累计 usage。cached input 不直接等于窗口占用。

`/export` 继续导出完整 system prompt 和活动 conversationHistory。可附加 tracker mode、预测值和失效原因，但不导出图片 hash 之外的敏感 payload，不把 usage 伪装成对话消息。

## 11. 实现任务与测试

### 任务 A：完整 request estimator

**文件：** 修改 `src/main/java/com/codeagent/memory/TokenBudget.java`；修改 `src/test/java/com/codeagent/memory/TokenBudgetTest.java`。

测试覆盖：文本与 role 开销、assistant tool-call arguments、嵌套 tool result、system + tools schema、图片/未知 part、空工具列表、序列化失败安全回退。

- [ ] 先为 `estimateMessageTokens`、`estimateToolsTokens`、`estimateRequestTokens` 写失败测试，确认旧实现缺少这些入口。
- [ ] 运行下面的定向测试，确认失败原因是新 API 或新断言尚未满足，而不是测试环境问题。
- [ ] 实现最小可用 estimator，并复用现有 `estimateMessagesTokens`/图片估算，避免出现两套字符算法。
- [ ] 再次运行定向测试，确认新增和既有用例全部通过。

```bash
mvn test -Dtest=TokenBudgetTest -DskipTests=false
```

预期：旧断言通过；新增断言固定 `estimateRequestTokens = messages + tools`。

### 任务 B：快照与 tracker

**文件：** 新增 `src/main/java/com/codeagent/context/RequestSnapshot.java`、`MeasuredUsage.java`、`InvalidationReason.java`、`ContextTokenTracker.java`、`RequestSnapshotFactory.java`；新增 `src/test/java/com/codeagent/context/ContextTokenTrackerTest.java`。

测试覆盖：

1. 无锚点返回 `FULL_ESTIMATE`。
2. 相同 envelope 追加 user 返回 `USAGE_ANCHORED_DELTA`。
3. system prompt 替换走 signed delta。
4. tools/provider/model/call config 改变走 full estimate。
5. compaction/clear/image prune/reorder 后锚点不可用。
6. usage 未知、非法或低于本地 anchor 时走 full estimate。
7. surface 缩短时 delta 为负，结果不小于 0。
8. assistant output 不重复计数。

- [ ] 先只创建测试，覆盖上述八种状态转换并确认失败。
- [ ] 实现 canonical 指纹和 immutable snapshot；测试相同内容不同对象仍得到相同指纹。
- [ ] 实现 `predict`、`recordSuccessfulCall` 和 `invalidate`，不引入消息副本字段。
- [ ] 运行测试，并通过反射/字段断言确认 tracker 不持有 `List<LlmClient.Message>`。

```bash
mvn test -Dtest=ContextTokenTrackerTest -DskipTests=false
```

### 任务 C：Provider usage

**文件：** 修改 `src/main/java/com/codeagent/llm/LlmClient.java` 和第 8.2 节列出的 provider；新增 `src/test/java/com/codeagent/llm/UsageNormalizationTest.java`。

每个 provider 测试 input scope、cache 不重复、缺失/非法 usage 不可信、output 只计一次。

- [ ] 为默认 `UNKNOWN/trusted=false` 写失败测试。
- [ ] 在 `LlmClient` 增加默认 `normalizeUsage` 与 `requestConfigurationFingerprint`。
- [ ] 按 provider API 已确认语义逐个覆盖；无法确认的 provider 保持默认路径。
- [ ] 运行 provider 定向测试，检查 cache bucket 没有重复加到 prompt pressure。

```bash
mvn test -Dtest=UsageNormalizationTest,DeepSeekClientTest,GLMClientTest -DskipTests=false
```

### 任务 D：Agent 请求边界

**文件：** 修改 `src/main/java/com/codeagent/agent/Agent.java`；新增 `src/test/java/com/codeagent/agent/AgentContextTrackingTest.java`。

测试覆盖：prediction 与 chat 使用同一 tools；snapshot 包含完整请求；长期记忆 top-k 变化不强制 full fallback；tool schema 变化强制 fallback；`/clear`、`/compact`、图片裁剪后下一请求 full estimate。

- [ ] 使用 fake `LlmClient` 捕获实际 messages/tools，先写 snapshot 与真实请求不一致时失败的测试。
- [ ] 增加 `historyVersion`，把所有 append/replace/clear/compact/image-prune 入口收敛到递增版本的方法。
- [ ] 将工具暴露移到 snapshot 之前，并保证同一列表实例或深度等价副本传给 `chat`。
- [ ] 接入 tracker；成功 response 使用发送前 snapshot 与 assistant estimate 建立锚点。
- [ ] 运行定向测试，额外检查 system prompt top-k 改变的第二轮模式为 `USAGE_ANCHORED_DELTA`。

```bash
mvn test -Dtest=AgentContextTrackingTest,MemoryManagerTest,ConversationHistoryCompactorTest -DskipTests=false
```

### 任务 E：压缩和 overflow recovery

**文件：** 修改 `src/main/java/com/codeagent/memory/AutoCompactionManager.java`、`ConversationHistoryCompactor.java`、`src/main/java/com/codeagent/agent/Agent.java`；新增 `src/main/java/com/codeagent/llm/ContextWindowExceededException.java` 和 `src/test/java/com/codeagent/agent/OverflowRecoveryTest.java`。

测试覆盖：未达阈值不摘要；先裁剪 tool result；摘要保持 pair 且必须变短；压缩后 tracker 失效；无 usage 的 overflow 可恢复；无 surface 变化不 retry；最多 retry 一次。

- [ ] 先写三组失败测试：正常 pressure、prune 后解除压力、provider overflow recovery。
- [ ] 改造 `AutoCompactionManager` 三参数入口，删除生产路径内部的第二套触发估算。
- [ ] 实现确定性 tool-result prune，并记录是否产生真实 surface 变化。
- [ ] 在 provider 错误解析边界归一化 `ContextWindowExceededException`。
- [ ] 在 `Agent` 实现最多一次、仅在 surface 变化后的 recovery retry。
- [ ] 运行定向测试，确认无变化时返回最初的 provider 异常实例或完整 cause chain。

```bash
mvn test -Dtest=AutoCompactionManagerTest,ConversationHistoryCompactorTest,OverflowRecoveryTest -DskipTests=false
```

### 任务 F：回归

- [ ] 运行常规回归；任何失败先判断是否为本次接口改造造成，不放宽原断言掩盖回归。

```bash
mvn test -Pquick
```

若状态栏/TUI 有改动：

```bash
mvn test -Pphase16-smoke
```

- [ ] 回归通过后同步 `docs/dev/06-memory-context.md`、`README.md`、`AGENTS.md` 中的 token/压缩事实描述。

## 12. 配置和默认值

第一阶段沿用：

```text
ContextProfile.compressionTriggerTokens()
AUTOCOMPACT_BUFFER_TOKENS = 13_000
大窗口摘要输出预留 = 20_000
CODEAGENT_SESSION_MEMORY_COMPACTION_ENABLED
```

可增加并提供默认值：

```text
codeagent.context.usage-anchor.enabled=true
codeagent.context.usage-anchor.max-positive-error=8192
codeagent.context.overflow.max-retries=1
```

按 provider/model 记录正误差 P95：

```text
error = measuredInputOrAnchor - predictedWithoutMargin
margin = clamp(P95(max(error, 0)), minMargin, maxMargin)
```

实现约束：

- 仅在 `MeasuredUsage.trusted=true`、input scope 已知且 envelope 与快照一致时写入样本；失败、取消、overflow 和不完整 SSE 不写样本。
- `predictedWithoutMargin` 使用本次成功请求的本地完整 request estimate 加 assistant surface 估算；不得用已加过 margin 的 `effectiveTokens` 反向生成样本。
- 每个 provider/model/call-config key 保留最近 32 个正误差样本（内存环形缓冲即可，第一阶段不持久化）；样本不足 4 个时使用 `minMargin=256` 的固定保守余量，最多不超过 `maxMargin=8192`。
- margin 只影响下一次 `effectiveTokens` 和诊断展示，不修改 provider 原始 usage、`AgentBudget` 累计值或 ledger 原始事件。

## 13. 完成定义

- 完整 estimator 覆盖 system/messages/tools/multimodal。
- provider usage 可信度和 input scope 有明确代码路径，未知语义不会被误用。
- 长期记忆 top-k 改变 system prompt 时可走 signed delta，不会每轮强制 full estimate。
- envelope、压缩、clear、图片裁剪、消息重排等结构变化安全回退。
- 自动压缩只读取统一预测值，压缩后重新 capture，旧锚点不继续累加。
- overflow recovery 只有在 surface 真正缩短后 retry，并保留原始错误。
- 压缩不写长期记忆、不删除 ledger 原始事件、不维护影子短期 history。
- 任务 A-F 的定向测试和 `mvn test -Pquick` 全部通过。
- `docs/dev/06-memory-context.md`、本文与 `08-harness-token-context-survey.md` 的描述一致。
\n> Durable session note: after restart, the first request uses `FULL_ESTIMATE`; a trusted provider usage response is required before rebuilding the token anchor.
