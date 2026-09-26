
# 长期记忆混合检索、写入解析与时间衰减重构方案

> 状态：已实现并于 2026-09-26 完成真实 BGE、针对性、quick、全量与构建验证
> 基线：main@d72d2bc07a6065241940e833da33c2b4394d07f9
> 目标分支：feat/long-term-memory-hybrid-retrieval
> 后续时间策略：乘法衰减与 lastConfirmedAt 由 docs/dev/24-long-term-memory-time-decay.md 接续设计并覆盖本文的 recency boost 部分。

## 1. 背景、目标与非目标

### 1.1 背景

当前长期记忆以 ~/.codeagent/memory/long_term_memory.json 为 source of truth，LongTermMemory 启动时全量加载到内存，MemoryRetriever 在每个用户 Turn / Plan Task 开始时从可见记忆中检索相关事实并按 Token 预算注入。

现有检索实现有以下已确认问题：

1. **只做词法匹配，不做语义召回。**
   MemoryQueryTokenizer 使用 jieba 分词，MemoryRetriever 通过 content.contains(token) 计算查询词覆盖率。只要用户换一种表达但关键词不重合，相关记忆就会得到 0 分。

2. **时间衰减公式与注释不一致。**
   当前实现：

~~~java
timeDecay = Math.max(0.5, 1.0 - ageHours / 24.0);
~~~

实际在 12 小时时已经降到 0.5，之后不再下降；源码注释却写成“24 小时内从 1.0 衰减到 0.5”。

3. **全文包含查询时绕过时间因素。**
   contentLower.contains(queryLower) 直接返回 1.0，使全文匹配和普通关键词匹配走两套不一致的排序逻辑。

4. **长期记忆统一乘以 1.2 对同源排序没有作用。**
   当前 retrieveLongTerm 对每条结果统一执行 score * 1.2，不会改变长期记忆之间的相对顺序。

5. **CLI 搜索与自动注入不是同一套排序。**
   自动注入走 MemoryRetriever；/memory search 最终走 LongTermMemory.search，后者只是集合遍历 + 子串命中 + 截断，用户看到的搜索结果与 Agent 实际注入的排序可能不同。

6. **现有去重只能识别格式差异和极窄的中文语法变体，不能处理真正的语义重复或事实更新。**
   MemoryDeduplicator 当前先做 NFKC、大小写、空白/普通标点规范化，再只容忍“的/地/得/是”这类少量语法助词差异。它刻意不做语义判断，因此“用户偏好 Java”与“Java 是用户首选语言”可能重复保存；“用户以前偏好 Java，现在改为 Python”也只会新增第二条 active 记忆，不会让旧事实失效。

同时，仓库已经具备随 JAR 分发的本地 InProcessBgeEmbeddingProvider（BGE-small-zh-v1.5 quantized，512 维）。因此旧设计文档中“长期记忆若使用向量检索必须新增远程 embedding 服务依赖”的前提已经不再成立。

### 1.2 目标

本次改造目标：

- 在不改变长期记忆 JSON source of truth 的前提下，将自动长期记忆检索升级为**本地语义 + 词法混合检索**。
- 对中文/英文语义改写具备召回能力，同时保留精确词法命中的可解释性。
- 不允许长期记忆正文因为检索而发送到远程 embedding 服务；默认只使用仓库已有的本地 BGE。
- 将“时间衰减”改成**有界的近因加成（recency boost）**：时间只用于打破相关度接近的候选，不能让一条仍然高度相关的旧事实因为年龄被大幅降权。
- 统一自动注入与 /memory search 的核心排名逻辑。
- embedding 不可用时自动降级为词法检索，不阻断 ReAct / Plan。
- 将长期记忆写入统一为 **CREATE / SUPERSEDE / DUPLICATE** 三种关系：同义重复不重复写入，用户明确更新偏好/事实时让旧记忆失效，新事实成为 active。
- 由 LLM 做“新建 / 更新 / 重复”的语义关系判断，但只在用户已经明确要求保存长期记忆后触发；不得从普通聊天自动更新长期事实。
- 用本地 embedding 只做候选旧记忆召回，不允许用 cosine 阈值直接决定删除、去重或覆盖。
- 保持 project/global scope、显式写入与 Token 注入预算等现有语义。

### 1.3 非目标

本次不做：

- 不修改 long_term_memory.json 文件格式，不迁移到 SQLite。
- 不把长期记忆改造成高频写入数据库。
- 不自动从普通对话抽取长期事实；仍遵守显式保存边界。
- 不复用代码 RAG 的 VectorStore 表结构；代码块索引与长期记忆生命周期不同。
- 不引入远程 embedding 作为 fallback。
- 不增加用户可见的复杂权重配置项；V1 使用内部常量和测试固定行为。
- 不做“普通聊天自动抽取并改写长期事实”；Memory mutation 仍必须来自用户明确的长期记忆保存/更新意图。
- 不做通用知识图谱、任意多事实逻辑冲突求解、importance 学习或自动遗忘；本次只解决显式长期记忆写入时的重复与 supersession。
- 不改短期会话 ParentConversationContext、Session Event Log 或 compaction 逻辑。

## 2. 现状分析

### 2.1 当前数据流

~~~mermaid
flowchart LR
    U[用户当前查询] --> T[MemoryQueryTokenizer]
    T --> R[MemoryRetriever]
    J[LongTermMemory JSON -> 内存 Map] --> S[project/global scope 过滤]
    S --> R
    R --> K[关键词覆盖率]
    K --> D[线性时间衰减]
    D --> Top[排序 Top K]
    Top --> B[Token 预算组装]
    B --> A[注入当前 user message]
~~~

当前词法分数近似为：

~~~text
exact full-query substring:
    relevance = 1.0

otherwise:
    keywordScore = matchedQueryTokens / queryTokens
    score = keywordScore * max(0.5, 1 - ageHours / 24)

final = score * 1.2
~~~

问题在于：

- “数据库持久化方案”与“使用 SQLite 保存 Plan 状态”即使语义接近，只要分词后没有共同 token，就无法互相召回。
- 时间不是事实正确性的可靠代理。长期记忆的典型内容是稳定偏好、项目约定和关键决策；30 天前的项目约定并不天然比今天的无关记忆更不重要。
- 当前时间公式会在 12 小时内把普通词法命中的分数最多砍半，影响过强，而且 exact-match 又完全绕过该逻辑。

### 2.2 现有可复用能力

仓库已有：

- EmbeddingProvider 抽象；
- InProcessBgeEmbeddingProvider：本地进程内 BGE，512 维，无网络依赖；
- 余弦相似度实现（当前位于代码 RAG 的 VectorStore）；
- MemoryQueryTokenizer 的 jieba 词法分词；
- project/global scope 过滤；
- MemoryRetrieverTest、LongTermMemoryTest 等测试基础。

因此本次不需要新增模型服务或外部向量数据库。

### 2.3 旧设计取舍需要更新

docs/dev/06-memory-context.md 第 10.2 节目前认为“长期记忆不用向量”的原因之一是 embedding 会引入额外服务依赖。该判断在当时实现下成立，但现在本地 BGE 已经是仓库既有基础设施。

仍然成立的约束是：

- 长期记忆规模小，不需要 ANN/FAISS 一类大型索引；
- 结果必须可解释、可审计；
- scope 和显式删除语义不能被向量检索破坏。

因此本方案不是“用向量替代关键词”，而是**词法信号继续保留，语义信号补足改写召回**。

### 2.4 当前写入与去重边界

当前 save_memory 只暴露 fact + scope。主 LLM 根据工具描述判断用户是否明确要求“记一下 / 记住 / 以后记得”等长期保存意图，随后 MemoryManager.storeFact() 直接创建新 MemoryEntry。

当前 MemoryDeduplicator 的职责只是“高精度、不误删”的确定性重复检查：

~~~text
same type/scope/project
        ↓
NFKC + lower-case + 普通格式差异归一
        ↓
canonical text 完全一致？
        ├─ 是 -> duplicate
        └─ 否
             ↓
仅多出 的/地/得/是 且长度/覆盖率满足阈值？
        ├─ 是 -> duplicate
        └─ 否 -> 两条都保留
~~~

它没有 embedding、没有 LLM，也不识别事实关系。这种设计安全但召回面过窄。

本次重构不把“去重”和“更新”设计成两套互相竞争的系统，而是统一成一次 **Memory Write Resolution**：

~~~text
incoming fact
   ↓
候选旧记忆召回
   ↓
关系判定
   ├─ DUPLICATE  -> no-op
   ├─ SUPERSEDE  -> old inactive + new active
   └─ CREATE     -> new active
~~~

也就是说，去重仍然存在，但它变成“写入关系判定”的一个结果，而不再是单独依赖中文语法差异规则的主机制。

## 3. 方案设计

### 3.1 总体架构

~~~mermaid
flowchart TB
    Q[当前 query] --> Scope[scope 过滤]
    M[LongTermMemory entries] --> Scope

    Scope --> Lex[Lexical scorer<br/>jieba + exact/coverage]
    Scope --> Sem[Semantic scorer<br/>local BGE + cosine]
    Q --> Lex
    Q --> Sem

    Sem -->|本地模型失败| Fallback[lexical-only]
    Lex --> Fuse[Hybrid relevance]
    Sem --> Fuse
    Fallback --> Fuse

    Fuse --> Recency[bounded recency boost]
    Recency --> Rank[稳定排序]
    Rank --> Budget[Token budget packing]
    Budget --> Inject[注入当前 user/task message]

    M --> Cache[MemoryEmbeddingCache<br/>进程内懒缓存]
    Cache --> Sem
~~~

核心原则：

1. **scope 先过滤，排名后执行。** 其他项目的 project memory 永远不能因为语义相似进入候选。
2. **词法与语义是并列信号。** 精确词法命中仍然具有高解释性；embedding 只补充语义改写。
3. **本地 embedding fail-open 到 lexical-only。** Memory 检索增强失败不能让主任务失败。
4. **时间只做轻量近因加成，不再大幅惩罚旧事实。**
5. **JSON 仍是唯一持久化事实源。** embedding 是可丢弃派生缓存。

### 3.2 新增 MemoryEmbeddingCache

建议新增：

~~~text
src/main/java/com/codeagent/memory/MemoryEmbeddingCache.java
~~~

职责：

- 懒加载 InProcessBgeEmbeddingProvider；
- 缓存可见 MemoryEntry 的 content embedding；
- 批量计算当前缺失向量，避免每次 query 重新 encode 全部长期记忆；
- query embedding 每次检索只计算一次；
- embedding 失败返回 unavailable，由调用方降级词法检索。

缓存 key 不只使用 memory id，而使用：

~~~text
entryId
+ SHA-256(content)
+ embeddingSpaceDescriptor
~~~

理由：

- 同一个 id 被替换正文后不能复用旧向量；
- 本地模型版本/维度变化后不能复用旧空间；
- 不需要和 LongTermMemory.store/delete/clear 建立脆弱的双写关系。

V1 只做**进程内缓存**，不持久化 embedding。长期记忆设计目标仍是几十到低百条稳定事实；启动后第一次语义检索批量补齐一次即可。这样避免为派生向量新增第二套持久化一致性协议。

如果未来实际数据证明冷启动 embedding 成本不可接受，再单独设计 sidecar index；本次不提前复杂化。

### 3.3 词法相关度

保留现有可解释信号，但统一成 [0, 1]：

~~~text
if normalizedContent contains normalizedQuery:
    lexical = 1.0
else:
    lexical = matchedQueryTokens / max(1, queryTokenCount)
~~~

要求：

- 空 query 返回 0，不允许除零。
- 不在此阶段应用时间衰减。
- exact match 不再提前 return 整个最终分数，只决定 lexical signal。
- 删除当前无意义的统一 * 1.2。
- V1 自动注入仍以正文为主要相关度信号；metadata 只用于 scope / 管理，不把项目路径等 metadata 当语义正文。

### 3.4 语义相关度

使用 InProcessBgeEmbeddingProvider：

~~~text
queryVector = embed(query)
entryVector = cachedEmbed(entry.content)
semantic = cosine(queryVector, entryVector)
~~~

语义分数归一化为：

~~~text
semantic = clamp(cosine, 0, 1)
~~~

不接受远程 provider fallback。原因：

- 长期记忆可能包含用户偏好、私有项目事实；
- 本地 BGE 已经可用；
- Memory 的“增强检索失败”应是可降级能力，而不是网络依赖。

### 3.5 混合相关度

V1 使用简单、可解释的线性融合：

~~~text
hybridRelevance =
    0.45 * lexicalScore
  + 0.55 * semanticScore
~~~

如果 semantic unavailable，则**重新归一化为 lexical-only**：

~~~text
hybridRelevance = lexicalScore
~~~

不能在 embedding 失败时仍保留 0.45 * lexical，否则会无意义地把所有词法结果整体压低。

为什么语义略高于词法：

- 本次改造的主要缺陷就是 paraphrase 召回；
- 精确全文命中仍有 lexical=1.0，且通常 semantic 也会较高；
- 词法仍占接近一半，不会被向量相似度完全替代。

实现采用保守初始阈值，并用真实本地 BGE golden test 固化边界：

~~~text
SEMANTIC_MIN_SCORE = 0.475
WRITE_CANDIDATE_MIN_SCORE = 0.45
~~~

普通自动注入要求 lexicalScore > 0 或 semanticScore >= 0.475。写入关系解析只做候选生成，阈值放宽到 0.45，因为后面还有严格的 LLM relation classifier，不会仅凭 cosine 覆盖旧记忆。

仓库新增 MemoryEmbeddingGoldenTest，直接使用 InProcessBgeEmbeddingProvider 验证三组应召回样例不低于阈值、两组无关样例低于阈值。2026-09-26 首次真实运行得到正例 `0.6601 / 0.5741 / 0.4845`、负例 `0.4672 / 0.4308`，据此将普通阈值校准为 `0.475`，写入候选阈值校准为更宽松的 `0.45`。

候选进入普通检索最终排序需满足：

~~~text
lexicalScore > 0
OR
semanticScore >= SEMANTIC_MIN_SCORE
~~~

### 3.6 时间策略：从“衰减惩罚”改成“有界近因加成”

长期记忆通常表示仍然有效的事实。时间只能说明“新”，不能说明“更相关”或“更正确”。因此不再使用：

~~~text
relevance * timeDecay
~~~

改为：

~~~text
ageDays = max(0, now - timestamp)

recencySignal =
    exp(-ln(2) * ageDays / 30)

recencyBoost =
    0.05 * recencySignal

finalScore =
    hybridRelevance + recencyBoost
~~~

含义：

- 新写入记忆最多获得 +0.05；
- 30 天后近因加成减半为 +0.025；
- 60 天后为 +0.0125；
- 很旧的记忆近因加成趋近 0，但**相关度本身不会因为年龄被砍掉**。

这样一条高度相关的旧项目约定不会输给一条仅仅“更新”的弱相关事实；时间只在相关度接近时提供稳定的次级排序信号。

该公式同时解决当前实现的两个问题：

- 不再存在“注释说 24h、实际 12h 触底”的错误；
- exact match 与关键词/语义结果都经过同一 finalScore 逻辑，不再有提前 return 绕过时间策略。

为保证测试确定性，MemoryRetriever 不直接调用 System.currentTimeMillis()，改为注入 Clock：

~~~java
MemoryRetriever(LongTermMemory memory, MemoryEmbeddingCache embeddings, Clock clock)
~~~

生产构造使用 Clock.systemUTC()；测试使用固定 Clock。

### 3.7 稳定排序

最终排序：

1. finalScore 降序；
2. hybridRelevance 降序；
3. timestamp 降序；
4. id 升序。

必须定义 tie-breaker，避免 ConcurrentHashMap.values() 的遍历顺序影响结果和测试。

### 3.8 自动注入与 CLI 搜索统一

当前：

~~~text
Agent/Plan injection -> MemoryRetriever
/memory search       -> LongTermMemory.search
~~~

改为：

~~~text
Agent/Plan injection ┐
                     ├-> MemoryRetriever ranked retrieval
/memory search       ┘
~~~

LongTermMemory 保持“存储 + scope + 基础过滤”的职责，不再承担用户可见的相关度排序。

MemoryManager.searchLongTerm(query, limit) 应改为委托 MemoryRetriever.retrieveLongTerm(query, limit, currentProject)。

这样用户通过 CLI 查到的顺序与 Agent 实际召回顺序一致。

### 3.9 Token 预算打包

本次不改变长期记忆注入上限来源，但顺手修正一个与排名直接相关的边界：

当前 buildContextForQuery 遇到第一条超预算记忆就 break，即使后面有更短的相关条目也不会继续尝试。

建议改为：

~~~text
for ranked entry:
    if entry fits remaining budget:
        append
    else:
        continue
~~~

这样“单条过大”不会饿死后续可容纳候选。

仍然保持最终注入顺序为排名顺序。

### 3.10 生命周期与资源释放

InProcessBgeEmbeddingProvider 内部持有 executor，因此不能每次检索创建新实例。

建议：

- MemoryManager 持有一个 MemoryRetriever；
- MemoryRetriever 持有一个懒加载的 MemoryEmbeddingCache；
- 应用生命周期结束时允许显式 close()；
- 如果当前入口没有统一 Memory close 生命周期，provider 的线程本身已是 daemon，但实现阶段仍应优先补齐可关闭接口，避免测试资源泄漏。

不允许每个 query new InProcessBgeEmbeddingProvider()。

### 3.11 统一长期记忆写入：MemoryWriteResolver

新增一个统一写入解析层，建议：

~~~text
src/main/java/com/codeagent/memory/MemoryWriteResolver.java
src/main/java/com/codeagent/memory/MemoryRelationClassifier.java
~~~

长期记忆写入不再是“先由一个独立 Deduplicator 判断，再无条件 append”，而是：

~~~mermaid
flowchart TB
    U[用户明确要求记住/更新长期事实] --> A[主 LLM 调用 save_memory]
    A --> R[MemoryWriteResolver]
    R --> F[确定性 exact fast-path]
    F -->|完全等价| D[DUPLICATE -> no-op]
    F -->|非完全等价| C[同 scope/project 的 active 候选召回]
    C --> E[local embedding + lexical Top-K]
    E --> L[MemoryRelationClassifier<br/>无工具 LLM]
    L -->|DUPLICATE| D
    L -->|SUPERSEDE| S[旧记忆 -> superseded<br/>新记忆 -> active]
    L -->|CREATE| N[新增 active memory]
~~~

这里的核心是：**去重和更新是同一写入决策的不同结果**。不再额外建设一套“语义去重器”。

### 3.12 显式记忆意图与 LLM 判定边界

主 LLM 继续负责判断当前用户是否明确要求长期保存。普通对话不能因为模型“觉得有用”就写入或更新长期记忆。

允许进入 MemoryWriteResolver 的前提仍是主 LLM 已调用 save_memory，例如：

~~~text
“记住，以后我更喜欢 Python”
“更新一下我的偏好，我现在不用 Java 了”
“之前记的 Java 偏好改成 Python”
~~~

而下面这些不应触发长期记忆写入：

~~~text
“我今天在写 Python”
“Python 也挺好”
“把这段 Java 改成 Python”
~~~

不建议只用 contains("改成") / 正则作为通用意图分类器；自然语言表达变化太大，而且容易把代码修改任务误判为长期偏好更新。

同时也不能只信任主 LLM 自己给出的“update”结论。更稳健的职责划分是：

1. **主 LLM：** 决定是否存在明确的长期记忆保存意图，调用 save_memory。
2. **MemoryWriteResolver：** 在同 scope/project 的 active memory 中找候选。
3. **MemoryRelationClassifier：** 根据用户当前 submittedInput、incoming fact 和候选旧事实，严格判断 CREATE / SUPERSEDE / DUPLICATE。
4. **后端确定性校验：** 检查 target memory 是否真实存在、仍为 active、scope/project 一致，并验证 SUPERSEDE 的 evidence 确实来自当前 submittedInput。

为防止模型从历史上下文自行“推断用户改主意了”，SUPERSEDE 输出必须带 evidence，且 evidence 必须是当前 submittedInput 的非空原文子串。

建议严格输出：

~~~json
{"action":"supersede","targetId":"fact-1234","evidence":"以后不要记我喜欢 Java 了，我现在更喜欢 Python"}
~~~

或：

~~~json
{"action":"duplicate","targetId":"fact-1234"}
~~~

或：

~~~json
{"action":"create"}
~~~

禁止 confidence、自由解释和额外字段；非法输出按 CREATE 处理，**但绝不能因 classifier 失败自动 supersede 旧记忆**。

### 3.13 候选召回不是最终去重结论

本地 embedding 的职责只到“找出可能谈论同一主题的旧事实”为止。

例如以下两条向量可能都很接近：

~~~text
用户喜欢 Java
用户不喜欢 Java
~~~

以及：

~~~text
项目使用 Java 17
项目使用 Java 21
~~~

所以绝对禁止：

~~~text
cosine >= threshold
=> duplicate / delete old memory
~~~

候选召回只在同一 visibility domain 内执行：

~~~text
same MemoryType
+ same scope
+ if project: same normalized project path
+ status == active
~~~

然后取 Top-K 候选交给 MemoryRelationClassifier。

确定性 canonical equality 可以保留为低成本 fast-path：

- NFKC；
- lower-case；
- 空白与无语义格式标点归一；
- 保护 C++、Java 17、URL、版本号等有意义符号。

但**删除当前“只容忍 的/地/得/是 + 82% 覆盖率”的 grammatical variant 主判定**。同义改写统一交给候选召回 + LLM relation classification。

因此新版 MemoryDeduplicator 可缩减为 ExactMemoryEquivalence（或并入 MemoryWriteResolver），只负责“确定性完全等价”的 no-op，不再承担自然语言近似去重。

### 3.14 ACTIVE / SUPERSEDED 生命周期

为了让“用户以前喜欢 Java、现在喜欢 Python”不同时进入正常检索，MemoryEntry 需要有逻辑状态。

V1 不增加新的顶层 JSON schema 字段，复用现有 metadata：

~~~text
metadata.status = active | superseded
metadata.supersededBy = <new-memory-id>     # 仅旧记录
metadata.supersedes = <old-memory-id>       # 仅新记录，可选
~~~

兼容规则：

- legacy memory 没有 status -> 按 active 处理；
- 普通检索 / buildContextForQuery / Memory candidate retrieval -> 只看 active；
- /memory list 可以继续显示全部，并标明 superseded，方便审计；
- /memory search 默认只搜索 active；若未来需要历史查询再单独设计参数。

SUPERSEDE 必须原子地完成：

~~~text
1. 校验 old active + 同 domain
2. 创建 new active memory
3. old.status = superseded
4. old.supersededBy = new.id
5. new.supersedes = old.id
6. 一次持久化成功后才返回成功
~~~

当前 LongTermMemory.store() 是内存 put 后全量写 JSON；实现阶段需要新增一个 synchronized 的 replace/supersede 操作，避免“新记忆已写入但旧记忆仍 active”的半更新状态。

如果持久化失败，内存状态也必须回滚到调用前快照；这比当前 store() 失败只 warn 的语义更严格，因为 supersession 是多条记录的一致性操作。

### 3.15 ReAct / Plan 一致性

现状：

- ReAct：按完整当前 user input 查询长期记忆；
- Plan Task：按 task.getDescription() 查询长期记忆；
- Reviewer：不检索长期记忆。

本次保持该边界，只替换底层 MemoryRetriever 排名能力。

历史对话、Planner Top-level Conversation、tool result 均不自动写入长期记忆，也不会成为 embedding 索引数据源。

### 3.16 失败与降级

| 场景 | 行为 |
|---|---|
| 本地 BGE 初始化失败 | 记录 warn，当前检索 lexical-only |
| query embedding 失败 | lexical-only |
| 某条 entry embedding 失败 | 跳过该条 semantic signal，该条仍可词法召回 |
| relation classifier 超时/非法 JSON | 不允许 supersede；退化为 CREATE 或 exact duplicate no-op |
| SUPERSEDE target 不存在/非 active/跨 scope 或跨 project | 拒绝 supersede，不修改旧记忆 |
| SUPERSEDE evidence 不属于当前 submittedInput | 拒绝 supersede，不修改旧记忆 |
| supersession 持久化失败 | 回滚本次多记录内存修改，旧事实保持 active |
| JSON 长期记忆加载失败 | 保持现有行为，不由检索层改变 |
| query 为空 | 返回空结果 |
| scope 不匹配 | 排名之前过滤，绝不进入 embedding/候选 |
| semantic 分数低于阈值且 lexical=0 | 不召回 |
| 记忆超过 Token 剩余预算 | 跳过该条，继续尝试后续候选 |

降级必须是**局部能力降级**，不能让主 Agent Turn 因 embedding 异常失败。

### 3.17 隐私与安全

长期记忆比代码 RAG 更可能包含用户偏好、账号使用习惯和私有项目事实，因此 V1 明确：

- 只使用 EmbeddingLocality.IN_PROCESS 的 provider；
- 不读取代码 RAG 的“已授权远程 embedding”配置作为 Memory 授权；
- 不把 Memory 正文发往远端；
- embedding cache 仅存在当前进程内，不新增包含 Memory 内容/向量的持久化文件。

若未来要支持远程 Memory embedding，必须单独设计用户授权与数据边界，不能复用代码索引授权隐式扩权。

## 4. 实现任务与测试矩阵

### 4.1 预计改动

生产代码：

~~~text
src/main/java/com/codeagent/memory/MemoryRetriever.java
src/main/java/com/codeagent/memory/MemoryManager.java
src/main/java/com/codeagent/memory/LongTermMemory.java
src/main/java/com/codeagent/memory/MemoryEmbeddingCache.java
src/main/java/com/codeagent/memory/MemoryWriteResolver.java
src/main/java/com/codeagent/memory/MemoryRelationClassifier.java
src/main/java/com/codeagent/memory/MemoryDeduplicator.java
src/main/java/com/codeagent/tool/ToolRegistry.java
src/main/java/com/codeagent/agent/Agent.java
src/main/java/com/codeagent/agent/PlanExecuteAgent.java
src/main/java/com/codeagent/cli/Main.java
~~~

可能抽取共享数学函数，但不要让 memory 依赖代码 RAG 的 VectorStore。如需复用 cosine，优先抽成不带领域语义的基础函数，避免反向依赖。

实现完成后同步：

~~~text
docs/dev/06-memory-context.md
docs/agents-reference.md
AGENTS.md（仅当运行时约束描述需要更新）
~~~

### 4.2 Golden retrieval cases

新增一组固定的真实本地 BGE 检索样例，至少覆盖：

**应语义召回：**

- Memory：Plan 状态使用 SQLite 持久化，支持任务节点恢复
  Query：之前的任务恢复机制把计划状态存在哪里？

- Memory：默认使用中文回答用户
  Query：后续都用汉语和我沟通

- Memory：项目要求 Java 17
  Query：这个仓库需要哪个 JDK 版本？

**不应误召回：**

- Memory：默认使用中文回答用户
  Query：修复 Maven 编译失败

- Memory：项目要求 Java 17
  Query：解释 Plan DAG 的资源冲突检测

- 不同 project scope 的高语义相似记忆必须完全不可见。

这些样例用于确定并锁定 SEMANTIC_MIN_SCORE，不能只 mock embedding 后宣称真实本地模型阈值有效。

### 4.3 单元测试

扩展 MemoryRetrieverTest：

- exact lexical match 仍排在明显无关结果前；
- paraphrase 在词法 0 命中时可由 semantic 召回；
- embedding failure 自动 lexical-only；
- exact match 不再绕过统一 final score；
- 固定 Clock 下 recency boost 符合 0 / 30 / 60 天半衰期；
- 老的高相关记忆不会因时间输给新的弱相关记忆；
- final score tie 时排序稳定；
- project/global scope 在 semantic retrieval 前过滤；
- semantic-only 结果低于阈值不注入；
- 单条过大时跳过并继续装入后续较小条目。

新增 MemoryEmbeddingCacheTest：

- 相同 id/content/space 只 embed 一次；
- content 变化重新 embed；
- embedding space 变化缓存失效；
- batch missing entries；
- provider exception 不污染已有 cache；
- close 释放 provider。

新增 MemoryWriteResolverTest / MemoryRelationClassifierTest：

- canonical exact equivalent 直接 DUPLICATE，不调用 classifier；
- 真正同义改写可判 DUPLICATE；
- “以前 Java，现在改 Python”在当前输入有明确更新证据时判 SUPERSEDE；
- “也喜欢 Python”判 CREATE，不覆盖 Java；
- “今天在写 Python”不会进入 save/update 流程；
- 高 embedding 相似度但含否定/版本变化时不会仅凭 cosine 去重；
- classifier 非法输出/异常绝不 supersede；
- SUPERSEDE target 必须 active 且同 scope/project；
- evidence 必须是当前 submittedInput 的原文子串；
- superseded memory 不参与正常 retrieval；
- legacy 无 status memory 按 active 处理；
- supersession 写盘失败回滚内存状态。

扩展 MemoryManagerTest：

- searchLongTerm 与自动注入使用同一排名入口；
- current project + global 可见性不变；
- save_memory 的统一写入路径可返回 CREATED / DUPLICATE / SUPERSEDED。

保留并回归 LongTermMemoryTest：

- JSON 顶层结构兼容，legacy metadata 无 status 仍可加载；
- exact duplicate 仍能确定性 no-op；
- supersession 多记录更新具备回滚保证；
- scope 行为不变。

### 4.4 针对性验证

实现阶段至少运行：

~~~bash
mvn test -Dtest=MemoryRetrieverTest,MemoryEmbeddingCacheTest,MemoryEmbeddingGoldenTest,MemoryWriteResolverTest,MemoryRelationClassifierTest,MemoryManagerTest,LongTermMemoryTest
mvn test -Dtest=InProcessBgeEmbeddingProviderTest
~~~

然后按仓库门禁：

~~~bash
mvn test -Pquick
mvn test -DskipTests=false
mvn clean package
git diff --check
~~~

真实 BGE golden cases如果因运行环境缺少模型资源而无法执行，必须明确报告，不得用 mock 结果替代阈值校准结论。

## 5. 兼容性、迁移与回滚

### 5.1 持久化兼容

long_term_memory.json 不变：

- 不新增 embedding 字段；
- 不改变 id/type/timestamp/tokenCount 顶层字段；
- metadata 允许新增 status / supersededBy / supersedes；旧用户文件缺少 status 时按 active 读取；
- 旧用户文件直接加载，无需离线迁移；
- 回滚到不认识 status 的旧代码时 superseded 条目可能重新被视为普通记忆，因此“代码回滚后行为完全等价”不成立；如需回滚必须同时提供 metadata 清理脚本或在合并前确认该兼容风险。

### 5.2 行为变化

预期行为变化：

- 语义改写可以召回过去词法 0 命中的记忆；
- 时间不再把普通相关结果在 12 小时内最多砍半；
- CLI /memory search 结果会与 Agent 自动检索排序对齐；
- 同义重复保存会 no-op，而不是依赖中文助词差异规则；
- 用户明确更新长期偏好/事实时，旧 active memory 会进入 superseded 状态，新事实成为 active；
- embedding 失败时检索行为接近旧版词法路径；写入关系 classifier 失败时绝不自动覆盖旧记忆。

### 5.3 性能

长期记忆规模当前设计为少量稳定事实，因此：

- scope 后对可见 entries 做本地 embedding/cosine 是可接受的；
- entry embedding 使用进程内 cache；
- 不建设 ANN 索引；
- query embedding 每次检索一次。

实现阶段应补一个小规模基准或至少日志度量首次冷检索与热缓存检索耗时；若真实延迟不可接受，再讨论持久化 sidecar，而不是提前引入数据库迁移。

### 5.4 回滚

回滚只需恢复旧 MemoryRetriever / MemoryManager 行为。由于持久化文件格式没有变化，不需要数据迁移或清理。

## 6. 实现顺序

1. 先补 MemoryRetrieverTest 的现状回归与时间公式测试，固定当前缺陷。
2. 新增 MemoryEmbeddingCache 和可注入的测试 provider。
3. 加本地 BGE semantic scoring 与 lexical-only fallback。
4. 加 hybrid relevance 与稳定排序。
5. 将时间策略替换为 bounded recency boost，并注入 Clock。
6. 新增 MemoryWriteResolver；把现有 Deduplicator 缩减为 deterministic exact-equivalence fast-path。
7. 新增无工具 MemoryRelationClassifier，严格输出 CREATE / SUPERSEDE / DUPLICATE，并落实 submittedInput evidence 校验。
8. 为 LongTermMemory 增加 ACTIVE / SUPERSEDED 兼容读取和原子 supersede + 写失败回滚。
9. 统一 MemoryManager.searchLongTerm 与自动注入排名入口，修正 Token budget packing 的 break -> continue。
10. 使用真实 BGE golden case 验证并校准 SEMANTIC_MIN_SCORE（当前实测值为 0.475）。
11. 跑 Memory targeted tests、quick、full、package、diff-check。
12. 同步 06-memory-context.md、AGENTS.md 与必要架构说明。

## 7. 验收清单

- [x] 语义改写无需共享关键词也能召回对应长期记忆。
- [x] 无关记忆不会因为 embedding 普遍正相似而被默认注入。
- [x] project scope 在任何语义计算前已经隔离。
- [x] Memory embedding 全程本地，不发生远程网络请求。
- [x] local BGE 故障时主任务继续，检索降级 lexical-only。
- [x] 时间公式由固定 Clock 单测覆盖，30 天半衰期行为与文档一致。
- [x] 后续时间策略按文档 24 改为有 0.6 下限的乘法衰减，不覆盖相关度主体。
- [x] exact match、lexical、semantic 统一走同一最终排序链路。
- [x] /memory search 与自动注入共享排名逻辑。
- [x] 单条超 Token 预算不会阻断后续较短候选。
- [x] 语义重复不再依赖“的/地/得/是”这类 grammatical-variant 规则。
- [x] 明确重复 -> DUPLICATE no-op；明确更新 -> SUPERSEDE；补充/无关新事实 -> CREATE。
- [x] SUPERSEDE 必须由当前用户输入提供明确 evidence，classifier/embedding 失败都不能自动覆盖旧事实。
- [x] superseded memory 不进入普通长期记忆检索，但仍可审计。
- [x] long_term_memory.json 顶层结构兼容，legacy 无 status 数据无需迁移。
- [x] 真实本地 BGE golden cases 已用于确定 semantic threshold。
- [x] 针对性测试、quick、full、package、git diff --check 全部通过后才能宣称实现完成。


## 8. 实际实现落点

本分支实现与上述设计对应关系：

- MemoryEmbeddingCache：复用 InProcessBgeEmbeddingProvider，entry 向量按 memory id + content SHA-256 + embeddingSpaceId 做进程内懒缓存；query 每轮重新 embedding。
- MemoryRetriever：0.45 lexical + 0.55 semantic；普通语义召回阈值 0.475，写入候选阈值 0.45；后续按文档 24 使用 30 天半衰期、0.6 下限的乘法衰减；排序含稳定 tie-breaker；Token packing 遇到过大条目改为 continue。
- MemoryWriteResolver：统一 CREATE / DUPLICATE / SUPERSEDE 写入路径；先做 deterministic exact-equivalence fast-path，再在同 type/scope/project 的 active memory 中召回候选；写入解析整体串行化，避免 Plan 并行 Task 同时判定后写入造成语义重复，普通检索仍可并发。
- MemoryRelationClassifier：复用当前 LlmClient 做无工具严格 JSON 分类；SUPERSEDE 必须返回当前 submittedUserInput 的原文 evidence。
- LongTermMemory：metadata.status=active|superseded；legacy 无 status 按 active；supersession 在内存中成对修改并通过临时文件 + atomic move 持久化，失败回滚内存状态。
- MemoryDeduplicator：删除“的/地/得/是 + 82% 覆盖率”语法近似规则，只保留确定性 canonical equality。
- Agent / PlanExecuteAgent：在每个顶层 Turn 设置真实 submittedUserInput，供 supersede evidence 校验；Plan resume 和 replan 同步更新证据源。
- ToolRegistry / Main：save_memory 与 /save 均接入统一写入解析；/memory list 展示 active/superseded，/memory search 只返回 active 且复用混合排名。

### 8.1 验证结果（2026-09-26）

已新增针对性测试源码：

~~~text
MemoryEmbeddingCacheTest
MemoryEmbeddingGoldenTest
MemoryRetrieverTest
MemoryWriteResolverTest
MemoryRelationClassifierTest
LongTermMemoryTest
MemoryManagerTest
ToolRegistryTest
~~~

真实执行结果：长期记忆联合针对性测试 90 tests、0 failures、0 errors；`mvn test -Pquick` 1124 tests、0 failures、0 errors、4 skipped；`mvn test -DskipTests=false` 1175 tests、0 failures、0 errors、10 skipped；`mvn clean package -DskipTests` 构建成功；`git diff --check` 通过。首次真实 BGE 黄金测试暴露原 `0.65` 阈值无法召回设计中的同义改写，修复分支按完整黄金集分布校准为普通 `0.475`、写入候选 `0.45`。
