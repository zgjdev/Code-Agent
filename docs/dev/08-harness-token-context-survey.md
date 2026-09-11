# 开源 Harness 的 Token、上下文压缩与 Prompt 变化处理调研

> 调研日期：2026-09-11
>
> 本文先记录公开源码/官方文档已经证实的行为，再给出 CodeAgent 的实现修订建议。没有源码证据的地方不做“行业都这样”的推断。

## 1. 结论先行

本次重点核查了 DeepSeek Harness（`deepseek-ai/deepseek-harness`），并对照 Cline、Aider、Goose、SWE-agent、Roo Code、OpenHands 与 Codex 的公开资料。结论如下：

1. **成熟 Harness 不会只把“上一轮准确 input token + 本轮 user prompt 估算”作为完整算法。** ReAct 下一次请求可能新增 assistant 输出、tool call/result、system prompt、工具 schema、图片或资源，必须以“完整请求可见内容”作为校验对象。
2. **DeepSeek Harness 明确实现了 provider usage 锚点 + 可见 surface 有符号增量。** 这是对本项目此前设想最接近、也最严格的公开实现：只有请求 envelope 可比且 usage 足以覆盖本地锚点估算时才复用 provider usage，否则重新完整估算。
3. **system prompt 变化不等于基线失效。** 在 DeepSeek Harness 中，system prompt 是持久 session surface 的一个节点；只要 provider/model/call config/tools 等 canonical envelope 没变，system 节点替换会按新旧 surface 的 token 差值计入 projected pressure，不需要因为每次长期记忆 top-k 改变而完全回退。
4. **“system prompt 变化过大”的固定比例阈值不是被调研产品普遍采用的规则。** DeepSeek 采用的是可比性校验、路由定价和安全下界；Cline/Aider/Roo 更倾向于直接完整估算；SWE-agent 则通过裁剪和 prompt-cache 控制变化。
5. **provider usage 不能代替下一次请求的 context 计算。** DeepSeek 将“最近一次 provider 报告的 prompt pressure”和“下一次请求 projected tokens”分开：前者是最新请求事实，后者是事实加当前 surface 的有符号变化。压缩判断读取后者的统一 `measure()`，而不是把 UI 上一次 usage 直接当作当前上下文。
6. **超限错误是独立的 recovery 信号。** DeepSeek、Cline、Roo Code 等公开 Harness 都保留“provider 已拒绝后再压缩并重试”的窄路径，不能只依赖请求前估算。

## 2. DeepSeek Harness：源码证实的设计

### 2.1 组件边界

DeepSeek 将 token 计算和压缩拆成两个可组合服务：

- `@deepseek-ai/dsh-token-meter`：回放持久 session log，生成一次一致的 pressure/surface 快照；不调用模型，不自己决定是否压缩。
- `@deepseek-ai/dsh-compaction-basic`：读取 token-meter 快照，按路由的 context window 和策略决定是否裁剪/摘要。

源码入口：

- [token-meter README](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/llm/token-meter/README.md)
- [TokenMeter.measure](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/llm/token-meter/src/index.ts#L124-L189)
- [compaction-basic policy](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/compaction/compaction-basic/src/config.ts#L12-L74)
- [compaction-basic trigger/recovery](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/compaction/compaction-basic/src/index.ts#L148-L224)

### 2.2 什么是 provider usage 锚点

TokenMeter 在最近一次成功的 `assistant/message` 前保存一个锚点：

```text
header                 = canonical request envelope
nodes                  = 该 assistant 请求实际看到的 surface（含 system/user）
assistantTokens        = assistant 输出的本地结构估算
usage                  = provider 返回的 usage（如果有）
```

`EpochHeader` 不是 system prompt，它只保存请求构造信息：

- `config`：provider、model、推理强度、采样参数等；
- `adapterDefaults`：由适配器解析出的默认字段；
- `tools`：按规范化顺序排列的完整工具 schema。

system prompt 属于 surface 中的 `system/message` 节点。DeepSeek 用 `canonicalHeader()` 和 `headerEquals()` 对 envelope 做字段级比较；工具 schema 按 JSON 内容和顺序比较，而不是比较对象身份。

参考：[request-header.ts](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/core/session/src/request-header.ts#L1-L67)、[session types](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/core/session/src/types.ts#L232-L255)。

### 2.3 基线复用和回退的精确条件

`TokenMeter.measure()` 的核心逻辑可以写成以下伪代码（变量名与源码含义一致）：

```text
currentHeader = requestHeader ?? replayedLatestHeader
currentSurface = priceSurface(replayedSurface, currentHeader.route)

if anchor exists && canonicalHeader(anchor.header) == canonicalHeader(currentHeader):
    anchorSurface = priceSurface(anchor.nodes, currentHeader.route)
                     + estimate(anchor assistant output)
    estimatedAnchor = estimateTools(currentHeader) + anchorSurface

    usageTokens = usage.inputTokens
                 + usage.cacheReadTokens
                 + usage.cacheWriteTokens
                 + usage.outputTokens

    if usage exists && usageTokens >= estimatedAnchor:
        baseline = provider usage         // 可复用的准确锚点
    else:
        baseline = estimatedAnchor        // provider usage 不足以覆盖本地锚点

    delta = currentSurface.tokens - anchorSurface.tokens
else:
    baseline = estimateTools(currentHeader) + currentSurface.tokens
    delta = 0

projectedTokens = max(0, baseline + delta)
```

这里有三个关键点：

1. **不是只加 user prompt。** `delta` 来自锚点之后整个 surface 的变化，包含 assistant 输出、tool call/result、注入消息、system 节点替换、图片和文件的路由定价。
2. **system prompt 变化不自动使 provider 锚点失效。** 它是 surface 差值的一部分；新旧 system 文本的变化会反映到 `delta`。真正使 usage 锚点不可复用的是 canonical envelope 不一致，或 provider usage 小于本地锚点估算。
3. **“provider usage 足够大”是保守性校验。** DeepSeek 文档明确承认固定启发式可能低估 CJK/JSON schema；当 provider usage 不能覆盖本地完整锚点时，宁可完整估算，也不把 usage 当成安全下界。

### 2.4 provider usage、projected tokens、composition 的分工

DeepSeek 没有把所有数字叫作“context tokens”，而是分成三种口径：

| 字段 | 含义 | 是否用于压缩决定 |
|---|---|---|
| `pressureTokens` | 最新请求的 provider prompt pressure：`input + cacheRead + cacheWrite`，不含 output | 否，主要展示最新事实 |
| `projectedTokens` | provider 锚点加当前 surface 有符号变化，代表下一次请求压力 | 是，`compaction-basic` 的 `measure().totalTokens` 同类值用于触发 |
| `contextBreakdown` | system/tools/message 的固定启发式构成 | 否，仅解释构成 |

`tokenUsage` 则是持久账本中的累计计费投影，保留 uncached input、output、cache read/write；重试通过 `llm/retry-started` 划分 attempt，不能因为最终摘要或 surface 替换而丢失历史计费。

参考：[usage-projection.ts](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/llm/token-meter/src/usage-projection.ts#L77-L218)。

### 2.5 DeepSeek 的本地估算到底怎么算

DeepSeek 的固定启发式不是“模型返回 token”，也不是远程 tokenizer：

```text
CHARS_PER_TOKEN = 4
BLOCK_OVERHEAD  = 4
ROLE_OVERHEAD   = 固定角色/消息封装开销
```

- 普通文本/reasoning：按字符密度加 block overhead；
- tool result：递归计算内部 content；
- system message：按渲染文本和 role framing 估算；
- tools schema：对规范化 JSON 做独立估算；
- 图片：如果当前路由声明视觉 token 定价，使用路由定价；否则走保守结构估算；
- file：按同一路由实际发送给模型的 handle 文本计价。

参考：[estimate.ts](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/llm/token-meter/src/estimate.ts#L12-L100)。文档明确提醒，4 字符/token 会严重低估 CJK 文本和 JSON schema，所以 provider usage 只在满足锚点下界时复用。

### 2.6 压缩触发时机和阈值

自动 pressure 检查挂在 `agent/pre-step`，但它读取的是**上一轮已经完成并写入日志的请求**以及已持久化的 tool result/steering，而不是在路由和请求尚未冻结时猜测。触发流程是：

1. 从 durable `request/header` 找到最近一次真实 provider/model route；
2. `tokenMeter.measure(session)` 回放当前 surface；
3. 通过 `llm.resolveModelInfo()` 获取该 route 的 context window；
4. 应用目标模型策略：默认 `thresholdRatio = 0.8`、`retainRatio = 0.16`；
5. `measurement.totalTokens >= floor(contextWindow * thresholdRatio)` 时，先进行可选的 tool-result pruner；
6. 重新测量，仍超阈值才选择保持 tool pair 完整的范围并调用摘要模型；
7. 摘要必须比被替换范围更小，压缩后重新测量；仍超阈值可按 `compactionRetries` 再做有限次压缩。

源码中的阈值和保留策略见 [config.ts](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/compaction/compaction-basic/src/config.ts#L12-L74)；触发和二次测量见 [index.ts](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/compaction/compaction-basic/src/index.ts#L249-L333)。

### 2.7 provider 超限后的 recovery

当适配器把上下文超限归一化为 `CONTEXT_WINDOW_EXCEEDED` 时，`agent/request-error` 监听器执行独立 recovery：

- 不要求已有 context capacity 或 provider usage；
- 绕过正常 0.8 pressure 阈值和保留 tail，先裁剪可裁剪的 tool result；
- 选择最大但 tool-pair-balanced 的可压缩范围，尝试摘要；
- 只有 `session.surface.replaceGeneration` 发生变化才返回 `{ kind: 'retry' }`；
- 默认最多 `maxOverflowRetries = 1`；没有可证明的持久变化就保留原始 provider 错误。

这避免了“估算低于阈值但 provider 仍拒绝请求”时死路，也避免摘要调用失败却无条件重试。参考：[after-call compaction and overflow recovery](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/.agents/notes/implemented/architecture/2026-07-10-after-call-compaction-pressure-and-overflow-recovery.md#decision)。

### 2.8 持久化和 prompt cache

DeepSeek 的 session 是 append-only durable log。压缩追加：

```text
compaction/start
compaction/summary   // summary、被 shadow 的 seq、token count、provider/model、usage
user/message         // surfaceOp: replace(startSeq, endSeq)
compaction/end
```

原始事件不删除；当前模型可见 surface 通过 replacement 投影得到，token usage 通过 projection 回放，因此压缩后仍能展示历史计费和审计信息。

摘要调用会重放当前 system prompt、tools 和待压缩消息作为前缀，只在末尾追加摘要指令，以尽量复用 provider KV/prompt cache。缓存优化影响成本和延迟，但不能成为正确性依据；上下文 pressure 仍由 token-meter 的统一回放计算。

## 3. 其他公开 Harness 的对照证据

| 产品 | token 计算 | 压缩/裁剪触发 | 与本问题直接相关的设计 |
|---|---|---|---|
| **Cline** | `estimateRequestInputTokens(systemPrompt, messages, tools)`，显式把 system 和 tools 作为 request overhead；对消息对象缓存估算结果 | 默认约 `maxInputTokens * 0.9`；provider 返回 context overflow 时进入 recovery | 以完整请求估算为主，没有 previous-usage + user-delta；overflow 有 deterministic basic compaction fallback。源码：[compaction.ts](https://github.com/cline/cline/blob/main/sdk/packages/core/src/extensions/context/compaction.ts) |
| **Aider** | 使用模型 tokenizer，逐条 tokenize 当前消息并求和；预留 512 token 输入缓冲 | 超预算后保留尾部、摘要前部；摘要仍超限则递归压缩 | 每次基于完整当前消息重算，没有发现上一轮 provider usage 增量基线。源码：[history.py](https://github.com/Aider-AI/aider/blob/main/aider/history.py) |
| **Goose** | `session.usage.total_tokens` 存在时优先使用；没有 usage 才调用本地 `count_context_tokens`（`tiktoken_rs::o200k_base`）；计入 system、messages、tools、resources、reply primer | 默认自动压缩阈值 80%；另有 summarize/truncate/clear/prompt 策略和 tool-call cutoff | 公开项目中最接近“provider usage 优先、估算 fallback”，但 usage 是 session 的 total context 口径，不能直接假定为上一轮 input baseline。文档：[Smart Context Management](https://github.com/block/goose/blob/main/documentation/docs/guides/sessions/smart-context-management.md)，源码：[ops_compaction.rs](https://github.com/block/goose/blob/main/crates/goose/src/agents/state_machine/ops_compaction.rs) |
| **SWE-agent** | 重点不是精确 token baseline，而是 history processors | `LastNObservations` 省略旧 observation；支持 polling；`CacheControlHistoryProcessor` 只给最近消息 cache control | 文档明确指出 history 每次改变都会破坏 prompt cache，因此通过 polling/选择性 elision 减少缓存失效。源码：[history_processors.py](https://github.com/SWE-agent/SWE-agent/blob/main/sweagent/agent/history_processors.py) |
| **Roo Code** | 优先调用 provider 的 `countTokens`；不可用时 tiktoken/启发式 fallback；system prompt、图片和结构开销单独计入 | 10% context buffer + `maxTokens` 输出预留；达到百分比或可用上限时 condense；失败回退 sliding-window truncation；context error 自动截断 25% 后重试 | 同时存在 provider 计数和本地安全上限，但仍按当前请求的完整 `totalTokens + lastMessageTokens` 判断，没有 system-delta 基线。源码：[context-management/index.ts](https://github.com/RooCodeInc/Roo-Code/blob/main/src/core/context-management/index.ts)，文档：[intelligent condensing](https://github.com/RooCodeInc/Roo-Code/blob/main/apps/docs/docs/features/intelligent-context-condensing.mdx) |
| **OpenHands** | 公共仓库公开了 context-window meter、condense API 和 UI；本次没有把前端显示当作后端 token 算法证据 | 提供手动/自动 condense 入口 | 算法细节需以其 agent-server 版本为准，不能仅凭 UI 推断 provider usage 复用规则。入口：[condense hook](https://github.com/All-Hands-AI/OpenHands/tree/main/src/hooks/mutation) |
| **OpenAI Codex CLI** | 开源仓库包含 `compact_remote_history`、`compact_token_budget`、token usage schema 等模块 | 有 durable context-compacted notification 和远端/本地历史压缩路径 | 说明“压缩状态、token usage、会话历史”应分离建模；具体阈值随版本演进，不能从模块名推导算法。入口：[codex-rs/core](https://github.com/openai/codex/tree/main/codex-rs/core) |

跨项目能确认的共同点只有：完整请求计量、阈值预留、结构化裁剪、超限 recovery、原始历史与活动视图分离。**没有证据表明主流 Harness 普遍采用固定的 system-prompt “变化过大”比例阈值。**

## 4. 对 CodeAgent 现有设计的修订

### 4.1 不再把“system prompt 变化过大”作为第一判断

长期记忆 top-k 每轮可能改变 system prompt，这是正常路径。建议采用 DeepSeek 的可比性模型：

```text
若 provider/model/call config/tools canonical envelope 相同，
    system prompt 变化作为 surface delta 计入；
若 envelope 改变、历史被压缩/清空、图片 payload 被裁剪、消息顺序重建，
    放弃 provider 锚点，完整估算当前请求。
```

不要用“新旧 system prompt token 净差超过 20% 就回退”作为通用规则。净差为零也可能是整段内容被同长度文本替换；真正需要判断的是请求是否仍可与锚点对齐、变化是否能被当前计量器定价。

### 4.2 建议的数据结构

新增轻量 `ContextTokenTracker`（不复制 `conversationHistory`）：

```java
record ContextAnchor(
    String provider,
    String model,
    String canonicalCallConfigHash,
    String toolSchemaHash,
    long anchorSurfaceTokens,
    long measuredInputTokens,
    long measuredOutputTokens,
    long measuredCachedInputTokens,
    long historyVersion,
    boolean usageTrusted,
    Instant measuredAt
) {}
```

另外保存当前请求的 `RequestSnapshot`：实际 system prompt、工具 schema、消息/多模态结构摘要、provider/model 和 history version。快照用于可比性和增量定位，不用于保存第二份短期消息历史。

### 4.3 建议的测量接口和口径

```text
ContextMeasurement measure(RequestSnapshot current) {
    currentSurface = fullEstimate(system + messages + tools + images + resources)

    if anchor.matchesCanonicalEnvelope(current)
       && anchor.usageTrusted
       && anchor.measuredTotal >= anchor.localSurfaceEstimate:
        return anchor.measuredTotal
             + (currentSurface - anchor.localSurfaceEstimate)

    return currentSurface
}
```

注意：CodeAgent 当前各 provider 返回字段的语义可能不同。若 provider 只有 `inputTokens`，不能直接照搬 DeepSeek 的 `input + cache + output` 总量；应根据该 provider 的请求边界计算：

- “上一请求 input + 本次请求前新增 assistant/tool 内容”是下一请求 context 的估计；
- `outputTokens` 只有在它尚未作为 history surface 计入时才能加入；
- `cachedInputTokens` 用于成本/缓存展示，是否计入 pressure 必须遵守 provider 的 context 语义；
- 未确认包含 tools/schema 的 usage 必须标记为低可信度并回退完整估算。

### 4.4 压缩判断的时机

测量应发生在每次实际 LLM 请求前、且以下内容都已冻结之后：

1. 本轮 user prompt 已展开（包括 MCP resource、本地文件和图片文本 fallback）；
2. 本轮已有 assistant/tool call/tool result 已写入短期 `conversationHistory`；
3. system prompt 已完成长期记忆检索和 PromptAssembler 组装；
4. 工具 schema、provider、model、输出预算已确定；
5. 再调用 `measure()` 与 `compressionTriggerTokens()` 比较。

如果 provider 仍返回 context overflow，走单独 recovery：先做确定性 tool-result pruning，再做平衡范围摘要；只有 surface 真正缩短才重试，并设置最大重试次数。

### 4.5 安全余量和阈值

当前 CodeAgent 的“大窗口 `window - 20k - 13k`”策略可以保留，解释为“摘要输出预算 + 安全缓冲”；不要把它和 provider usage、cached input 或 `MemoryEntry.tokenCount` 混为同一个数字。后续可按 provider/model 记录：

```text
predictionError = providerMeasuredInput - predictedWithoutMargin
safetyMargin    = clamp(P95(positive predictionError), min, max)
```

在没有足够样本时使用保守固定余量；只有 provider usage 可靠且 envelope 可比时才更新误差样本。

## 5. 实现步骤和验收标准

### 阶段一：统一计量，不改变压缩结果

- 在 `TokenBudget` 之外增加完整请求估算入口，显式计入 system、tools、tool result、图片和资源；
- 为 `LlmClient` usage 增加 provider/model/字段语义和可信度；
- 记录“预测值 vs provider 实际值”，仅用于诊断；
- 保持现有 `conversationHistory`、`ConversationLedger`、长期记忆语义不变。

### 阶段二：引入 usage 锚点和 surface delta

- 在 `Agent` 每次成功请求后保存 `ContextAnchor`；
- 对 canonical envelope 做内容级比较；
- system prompt top-k 替换作为 surface delta，不因对象替换或 setter 调用直接失效；
- 发生 compaction、`/clear`、model/provider/tools 改变、图片裁剪或消息重排时清除锚点；
- 锚点无法证明是安全下界时完整估算。

### 阶段三：压缩与 recovery 对齐公开 Harness

- pressure 检查放在实际请求边界，先确定性裁剪超大 tool result，再摘要；
- 摘要必须小于被替换范围，切点必须保持 tool-call/result 配对；
- 监听标准化 context-overflow 错误，压缩产生真实 surface generation 变化后才 retry；
- 压缩事件追加到 ledger，原始消息不删除，活动 `conversationHistory` 原地重建；
- `/context` 和状态栏同时展示 `estimated/projected` 与 `measured usage`，不要混称为同一 token。

验收重点：

- system prompt 每轮只改变 memory top-k 时，不应每轮都完整回退；
- tools schema 改变时必须回退；
- assistant tool call/result 增加时，预测值不能只增加 user prompt；
- provider usage 缺失、低于本地锚点或 envelope 不一致时，必须安全回退；
- provider 已报超限但没有 usage 时，仍能通过裁剪/摘要恢复或保留原错误；
- 压缩、分页、重连、重启后历史计费和活动上下文口径不混乱。

## 6. 参考资料（固定版本）

- DeepSeek Harness（commit `c291e7961a515f6d7af9304e7fd1d257929aef26`）
  - [Token meter README](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/llm/token-meter/README.md)
  - [TokenMeter implementation](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/packages/llm/token-meter/src/index.ts)
  - [Compaction subsystem](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/docs/subsystems/compaction.md)
  - [After-call pressure and overflow recovery note](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/.agents/notes/implemented/architecture/2026-07-10-after-call-compaction-pressure-and-overflow-recovery.md)
  - [Projected token usage note](https://github.com/deepseek-ai/deepseek-harness/blob/c291e7961a515f6d7af9304e7fd1d257929aef26/.agents/notes/implemented/architecture/2026-07-29-projected-token-usage-and-request-context.md)
- [Cline context compaction](https://github.com/cline/cline/blob/main/sdk/packages/core/src/extensions/context/compaction.ts)
- [Aider history management](https://github.com/Aider-AI/aider/blob/main/aider/history.py)
- [Goose smart context management](https://github.com/block/goose/blob/main/documentation/docs/guides/sessions/smart-context-management.md)
- [SWE-agent history processors](https://github.com/SWE-agent/SWE-agent/blob/main/sweagent/agent/history_processors.py)
- [Roo Code context management](https://github.com/RooCodeInc/Roo-Code/blob/main/src/core/context-management/index.ts)
- [Roo Code intelligent condensing](https://github.com/RooCodeInc/Roo-Code/blob/main/apps/docs/docs/features/intelligent-context-condensing.mdx)
- [OpenHands context/condense UI entry points](https://github.com/All-Hands-AI/OpenHands/tree/main/src/hooks/mutation)
- [OpenAI Codex core context modules](https://github.com/openai/codex/tree/main/codex-rs/core)
