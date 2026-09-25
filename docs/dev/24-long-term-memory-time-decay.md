# 长期记忆确认时间与乘法衰减方案

> 状态：已实现，待完整 Maven 回归验证
> 基线：main@ea890e0a00752ad3cc8bb9fef378ee5b17332f9d
> 目标分支：feat/long-term-memory-time-decay
> 前置能力：docs/dev/23-long-term-memory-hybrid-retrieval.md 已合并到 main

## 1. 背景、目标与非目标

### 1.1 背景

当前长期记忆检索已经采用：

- 0.45 * lexicalScore + 0.55 * semanticScore 的混合相关度；
- active / superseded 生命周期；
- CREATE / DUPLICATE / SUPERSEDE 统一写入解析；
- 30 天半衰期、最大 +0.05 的 recency boost。

当前最终分数为：

~~~text
hybridRelevance = 0.45 * lexical + 0.55 * semantic
recencyBoost = 0.05 * 2^(-ageDays / 30)
finalScore = hybridRelevance + recencyBoost
~~~

这个设计刻意保证“相关性优先”，但时间的最大影响只有 0.05。对于大多数 hybrid relevance 差距超过 0.05 的候选，时间无法改变排序，因此“长期未确认的记忆逐渐变弱”这一语义并不明显。

另一方面，直接按 MemoryEntry.timestamp（首次创建时间）衰减也不合理：一条一年前创建但用户昨天刚明确再次确认的偏好，不应该继续按“一年前”计算记忆年龄。

### 1.2 目标

本次改造目标：

1. 将普通长期记忆检索的时间策略从“小幅加分”改为**有下限的乘法衰减**。
2. 衰减下限固定为 **0.6**，30 天作为“高于下限部分”的半衰期。
3. 最终分数改为：

~~~text
decay(age) = 0.6 + 0.4 * 2^(-ageDays / 30)
finalScore = hybridRelevance * decay(age)
~~~

4. 新增逻辑确认时间 lastConfirmedAt，普通检索按最后确认时间而不是首次创建时间计算年龄。
5. 只有**用户显式再次保存同一长期事实**时才刷新 lastConfirmedAt：
   - deterministic exact duplicate；
   - relation classifier 判定 DUPLICATE。
6. 普通检索命中、上下文注入、Plan Task 使用、Tool 执行成功等都**不能**自动刷新确认时间，避免自我强化循环。
7. 写入关系候选召回不使用时间衰减，确保很旧的 active 记忆仍然能够作为 DUPLICATE / SUPERSEDE 候选。
8. 保持 active / superseded 对“事实是否有效”的职责：时间只影响检索强度，不会把 active 事实自动变成 superseded。

### 1.3 非目标

本次不做：

- 不引入 ACT-R / FSRS 的完整强度模型；
- 不记录自动检索次数或自动强化次数；
- 不根据时间自动删除记忆；
- 不自动把 active 记忆改成 superseded；
- 不改变 lexical / semantic 权重；
- 不改变 semantic threshold；
- 不迁移长期记忆到 SQLite；
- 不引入新的用户可配置参数；
- 不把 lastConfirmedAt 做成新的顶层 JSON schema 字段，继续放在 metadata 中。

## 2. 现状分析

### 2.1 当前检索链路

~~~mermaid
flowchart LR
    Q[Query] --> V[scope/status filter]
    V --> L[Lexical score]
    V --> S[Local BGE semantic score]
    L --> H[Hybrid relevance]
    S --> H
    H --> R[+ recency boost]
    R --> K[Stable ranking / Top-K]
~~~

当前 MemoryRetriever：

~~~text
hybrid = 0.45 * lexical + 0.55 * semantic
final = hybrid + recencyBoost(entry.timestamp)
~~~

recencyBoost 最大只有 0.05，且以首次写入时间为基准。

### 2.2 当前写入链路

~~~mermaid
flowchart LR
    U[Explicit save intent] --> W[MemoryWriteResolver]
    W --> E[Exact equivalence]
    E -->|same| D[DUPLICATE]
    E -->|different| C[Candidate retrieval]
    C --> R[MemoryRelationClassifier]
    R -->|duplicate| D
    R -->|supersede| S[SUPERSEDE]
    R -->|create| N[CREATE]
~~~

当前 DUPLICATE 是内容 no-op：不会新增条目，也不会记录“用户刚刚再次确认了这个事实”。

这正好是本次引入 lastConfirmedAt 的自然落点。

## 3. 方案设计

### 3.1 衰减公式

定义：

~~~text
DECAY_FLOOR = 0.6
DECAY_HALF_LIFE_DAYS = 30
~~~

公式：

~~~text
decay(ageDays) =
    DECAY_FLOOR
    + (1 - DECAY_FLOOR) * 2^(-ageDays / DECAY_HALF_LIFE_DAYS)

finalScore =
    hybridRelevance * decay(ageDays)
~~~

这里“30 天半衰期”指的是**高于 0.6 下限的那部分强度每 30 天减半**，不是总权重每 30 天减半。

典型值：

| age | decay factor |
|---:|---:|
| 0 天 | 1.00 |
| 30 天 | 0.80 |
| 60 天 | 0.70 |
| 90 天 | 0.65 |
| 120 天 | 0.625 |
| 很久以后 | -> 0.60 |

因此：

- 新确认记忆保持完整 hybrid relevance；
- 长期没有再次确认的 active 记忆逐渐降权；
- 再旧也不会低于原相关度的 60%。

### 3.2 为什么选择有下限乘法衰减

与当前 hybrid + 0.05 boost 相比，乘法衰减能让时间真正影响排序。

同时设置 0.6 floor 是为了避免：

~~~text
一条长期稳定且非常相关的旧事实
仅因为年龄
被衰减到接近 0
~~~

例如：

~~~text
old relevance = 0.98
very old decay = 0.60
final = 0.588

new relevance = 0.50
new decay = 1.00
final = 0.50
~~~

高度相关的旧事实仍然可以胜出。

相反，如果：

~~~text
old relevance = 0.82
very old final = 0.492

new relevance = 0.70
new final = 0.70
~~~

近期且仍较相关的记忆可以获得明显优势。

### 3.3 lastConfirmedAt 数据模型

不修改 MemoryEntry 顶层字段，继续使用 metadata：

~~~text
metadata.lastConfirmedAt = <ISO-8601 Instant>
~~~

语义：

- timestamp：MemoryEntry 首次创建时间，保持不可变；
- lastConfirmedAt：用户最后一次**显式确认同一事实仍然成立**的时间。

兼容规则：

1. 新创建 active memory：lastConfirmedAt = timestamp。
2. legacy memory 缺少 lastConfirmedAt：检索时回退 timestamp。
3. lastConfirmedAt 非法：回退 timestamp。
4. lastConfirmedAt 在未来：ageDays = max(0, now - lastConfirmedAt)，不产生大于 1 的 decay。
5. superseded memory 保留自己的历史 lastConfirmedAt 供审计，但不参加正常检索。

### 3.4 什么行为算“确认”

只有显式长期记忆写入流程中的 DUPLICATE 才算确认：

~~~text
用户明确要求记住/保存
        ↓
save_memory / /save
        ↓
MemoryWriteResolver
        ↓
exact duplicate 或 classifier DUPLICATE
        ↓
不新建 MemoryEntry
        ↓
更新 existing.metadata.lastConfirmedAt = now
~~~

CREATE：

~~~text
new.timestamp = now
new.lastConfirmedAt = now
~~~

SUPERSEDE：

~~~text
old -> superseded
new.timestamp = now
new.lastConfirmedAt = now
~~~

下列行为**绝不能**刷新 lastConfirmedAt：

- 自动检索到该记忆；
- 注入 prompt；
- LLM 使用了该事实；
- Plan Task 读取该事实；
- Reviewer 看到该事实；
- Tool 执行成功；
- 仅仅因为该记忆在排名中靠前。

这样避免：

~~~text
排名高
→ 更容易被检索
→ 自动刷新确认时间
→ 衰减更慢
→ 排名更高
~~~

这种自我强化反馈环。

### 3.5 写入候选召回不使用 decay

MemoryWriteResolver 的候选检索用于判断：

- incoming fact 是不是旧事实的同义重复；
- incoming fact 是否明确替代旧事实。

所以一个很旧的 active memory 仍必须有机会成为关系分类候选。

因此：

~~~text
普通 retrieval:
    final = hybrid * decay

write candidate retrieval:
    final = hybrid
~~~

不能因为旧记忆衰减到 0.6 就让它更难被 SUPERSEDE。

### 3.6 排序规则

普通长期记忆检索：

1. finalScore desc
2. hybridRelevance desc
3. lastConfirmedAt desc
4. timestamp desc
5. id asc

写入候选：

1. hybridRelevance desc
2. lastConfirmedAt desc
3. timestamp desc
4. id asc

lastConfirmedAt 只作为稳定 tie-breaker；主要影响已经体现在乘法 decay 中。

### 3.7 LongTermMemory 确认操作

新增同步操作：

~~~text
confirm(memoryId, confirmedAt)
~~~

要求：

1. target 存在；
2. target 仍 active；
3. confirmedAt 不为空；
4. 新确认时间不能让 lastConfirmedAt 倒退；
5. 更新 metadata 后一次持久化；
6. 持久化失败回滚内存状态；
7. 不改变 content / id / type / timestamp / tokenCount。

确认失败时 MemoryWriteResolver 不应假装“确认成功”。

### 3.8 完整检索数据流

~~~mermaid
flowchart TB
    Q[当前 Query] --> F[scope + active filter]
    F --> L[jieba lexical score]
    F --> E[local BGE semantic cosine]
    L --> H[0.45 lexical + 0.55 semantic]
    E --> H
    H --> T[lastConfirmedAt / fallback timestamp]
    T --> D[decay = 0.6 + 0.4 * 2^(-age/30)]
    D --> R[final = hybrid * decay]
    R --> K[stable Top-K]
    K --> B[token-budget packing]
~~~

## 4. 实现任务与测试矩阵

### 4.1 预计改动

生产代码：

~~~text
src/main/java/com/codeagent/memory/MemoryRetriever.java
src/main/java/com/codeagent/memory/LongTermMemory.java
src/main/java/com/codeagent/memory/MemoryWriteResolver.java
src/main/java/com/codeagent/memory/MemoryManager.java
~~~

测试：

~~~text
src/test/java/com/codeagent/memory/MemoryRetrieverTest.java
src/test/java/com/codeagent/memory/LongTermMemoryTest.java
src/test/java/com/codeagent/memory/MemoryWriteResolverTest.java
src/test/java/com/codeagent/memory/MemoryManagerTest.java
~~~

文档同步：

~~~text
docs/dev/06-memory-context.md
docs/agents-reference.md
AGENTS.md
~~~

### 4.2 MemoryRetrieverTest

至少覆盖：

- 0 天 decay = 1.0
- 30 天 decay = 0.8
- 60 天 decay = 0.7
- 90 天 decay = 0.65
- 很老的 memory decay 接近但不低于 0.6
- legacy 无 lastConfirmedAt 时使用 timestamp
- old timestamp + recent lastConfirmedAt 时使用 lastConfirmedAt
- future lastConfirmedAt 不产生 factor > 1
- 普通 retrieval 使用乘法 decay
- write candidate retrieval 不使用 decay
- 高相关旧 memory 在 floor 保护下仍可超过明显弱相关的新 memory
- 新近且相关度接近的 memory 可以超过长期未确认的 memory

### 4.3 LongTermMemoryTest

至少覆盖：

- 新 store 的 active memory 自动补 lastConfirmedAt=timestamp
- confirm 更新 lastConfirmedAt
- confirm 不改变 timestamp
- confirm 时间不能倒退
- confirm 后重新加载 JSON 仍保留
- inactive/superseded memory 不能 confirm
- legacy 无 lastConfirmedAt 仍可读取

### 4.4 MemoryWriteResolverTest

至少覆盖：

- exact duplicate 刷新 existing lastConfirmedAt，不创建新条目
- classifier DUPLICATE 刷新 existing lastConfirmedAt
- CREATE 的 lastConfirmedAt 等于创建时间
- SUPERSEDE 新记忆 lastConfirmedAt 等于新创建时间
- candidate retrieval 不因旧记忆年龄而漏掉关系目标

### 4.5 MemoryManagerTest

更新用户可见结果：

~~~text
DUPLICATE
=> 已确认已有长期记忆，不重复创建
~~~

避免继续显示“未重复保存”但实际上已经更新 lastConfirmedAt 的误导文案。

### 4.6 验证命令

~~~bash
mvn test -Dtest=MemoryRetrieverTest,MemoryWriteResolverTest,LongTermMemoryTest,MemoryManagerTest
mvn test -Pquick
mvn test -DskipTests=false
mvn clean package
git diff --check
~~~

## 5. 兼容性、迁移与回滚

### 5.1 JSON 兼容

不新增顶层字段。

旧数据：

~~~json
{
  "timestamp": "...",
  "metadata": {
    "scope": "global",
    "status": "active"
  }
}
~~~

仍可直接加载。

新数据：

~~~json
{
  "timestamp": "...",
  "metadata": {
    "scope": "global",
    "status": "active",
    "lastConfirmedAt": "2026-09-25T10:00:00Z"
  }
}
~~~

旧代码会忽略未知 metadata key，因此数据格式向后兼容；但旧代码仍会恢复旧 recency 算法，行为不兼容。

### 5.2 行为兼容

保持不变：

- lexical + local BGE hybrid relevance
- scope/project isolation
- active/superseded
- semantic threshold
- CREATE / DUPLICATE / SUPERSEDE
- Token budget packing
- JSON source of truth

发生变化：

- 时间从 +0.05 recency boost 改为 [0.6, 1.0] 乘法衰减；
- 时间基准从 creation timestamp 改为 lastConfirmedAt；
- DUPLICATE 从“完全无状态变化”变为“内容不重复创建，但刷新确认时间”。

### 5.3 回滚

代码回滚不会破坏 JSON：

- 新增的 metadata.lastConfirmedAt 会被旧实现忽略；
- 不需要数据迁移；
- 回滚后检索行为恢复旧的 +0.05 recency boost；
- 再切回新版时已记录的 lastConfirmedAt 仍然有效。

## 6. 风险与取舍

### 6.1 0.6 与 30 天仍是超参数

0.6 floor 和 30 天半衰期是明确的产品/工程策略，不应包装成理论最优值。

需要通过 ranking golden cases 观察：

- 老而高度相关；
- 新而中等相关；
- 同相关度不同确认时间；
- 极老但唯一精确命中；
- 新但语义较弱。

后续如需调参，应由测试固定预期排序，而不是只比较单个公式数值。

### 6.2 确认不等于自动访问

本设计有意不记录 retrieval access count。

如果未来升级到 ACT-R 风格 recency + frequency 强度模型，应新增“可信 reinforcement event”定义，不能直接把自动检索次数当成强化次数。

## 7. 验收清单

- [ ] 分支基于合并长期记忆混合检索后的最新 main。
- [ ] 普通检索 finalScore 使用 hybrid * decay。
- [ ] decay floor 固定 0.6。
- [ ] 30 天时 decay=0.8，60 天=0.7，90 天=0.65。
- [ ] lastConfirmedAt 优先于 timestamp。
- [ ] legacy 无 lastConfirmedAt 自动回退 timestamp。
- [ ] exact DUPLICATE 和 classifier DUPLICATE 都刷新 lastConfirmedAt。
- [ ] 自动 retrieval 不刷新 lastConfirmedAt。
- [ ] CREATE / SUPERSEDE 的新 active memory 初始化 lastConfirmedAt。
- [ ] 写入候选召回不应用 decay。
- [ ] active/superseded 仍决定事实有效性，时间不自动失效事实。
- [ ] confirm 持久化失败不会留下错误内存状态。
- [ ] 文档与运行时行为同步。
- [ ] targeted / quick / full / package / diff-check 有真实验证结果后才宣称全部通过。


## 8. 实际实现落点

本分支实现与方案对应关系：

- MemoryRetriever：删除 RECENCY_MAX_BOOST / recencyBoost；新增 DECAY_FLOOR=0.60、DECAY_HALF_LIFE_DAYS=30，普通检索使用 hybridRelevance * decayFactor；写入候选保持纯 hybrid 排名。
- LongTermMemory：新增 metadata.lastConfirmedAt 生命周期辅助字段和 confirm(id, Instant) 原子确认操作；新 active store 自动初始化确认时间；legacy / 非法确认时间回退 creation timestamp；确认时间不得早于创建时间或向后倒退。
- MemoryWriteResolver：注入 Clock；CREATE / SUPERSEDE 使用同一个 now 初始化 timestamp 与 lastConfirmedAt；exact DUPLICATE、classifier DUPLICATE 以及并发竞争后的 duplicate fallback 都刷新已有 active memory 的确认时间。
- MemoryManager：DUPLICATE 用户可见结果改为“已确认已有长期记忆，未重复创建”，与实际状态变化一致。
- 排序：普通检索按 finalScore、hybridRelevance、lastConfirmedAt、timestamp、id 稳定排序；写入候选按 hybridRelevance、lastConfirmedAt、timestamp、id 排序。
- 自动 retrieval 不调用 confirm，因此不会形成基于检索次数的自我强化循环。

### 8.1 新增/调整测试

本次扩展：

~~~text
MemoryRetrieverTest
LongTermMemoryTest
MemoryWriteResolverTest
MemoryManagerTest
~~~

覆盖：

- 0 / 30 / 60 / 90 天分别得到 1.0 / 0.8 / 0.7 / 0.65；
- 极老记忆 factor 不低于 0.6；
- lastConfirmedAt 优先于 creation timestamp；
- legacy / 非法确认时间回退；
- future confirmation 不产生 factor > 1；
- 普通检索应用 decay，写入候选不应用 decay；
- retrieval 不刷新 lastConfirmedAt；
- exact / semantic DUPLICATE 刷新确认时间；
- CREATE / SUPERSEDE 初始化新记忆确认时间；
- confirm 持久化并保持 creation timestamp 不变；
- superseded memory 不可再次确认；
- MemoryManager DUPLICATE 回执与确认语义一致。

### 8.2 当前验证边界

当前工具环境没有可直接执行该仓库完整 Maven 工作树的运行入口，因此本轮没有真实执行 targeted / quick / full / package。已执行静态结构检查、公式数值核对和修改文件一致性检查；最终交付不得把“测试源码已补齐”描述成“测试已通过”。

合并前仍需实际执行第 4.6 节命令。
