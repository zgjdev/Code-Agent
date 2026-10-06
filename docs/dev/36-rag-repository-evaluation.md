# 真实仓库 RAG 离线评测

文档编号接续目录中现有的 35；本文件是本任务唯一的设计与实施文档。

## 1. 背景、目标与非目标

第一阶段已完成并提交为 `8483ea4`：增加可复现的检索评测，未修改生产行为，历史结果保留在第 4.4～4.7 节。用户随后明确要求删除 Graph 召回、修正已发现的明显问题并复测，第二阶段方案见第 3.4 节，实施结果追加在第 4.8 节。两阶段均不调用远程 Embedding 或付费 LLM。最终回答正确率不等同于检索精确率。

第一阶段目标：72 个固定问题（60 个单证据、6 个多证据、6 个无答案），另设 6 个独立纯符号关系探针；覆盖真实生产 Java 代码，报告 P@5、标注证据 Recall@5/10、Hit@1/5/10、MRR@10、折扣证据覆盖分、完整标注证据命中率、无结果率、无答案误返回率和暖流水线 P50/P95。历史对比词法、语义、词法+语义、词法+Graph 和完整三路；当前双路复测见第 4.8 节。

## 2. 现状分析（源码证据、已知约束）

### 2.1 架构位置

第一阶段源码：`DefaultCodeRetrievalService` 组装 Term FTS、Graph、Semantic，随后调用 `RetrievalFusion` 和 `RetrievalBudget`。第二阶段移除 Graph，保留 Term FTS 与 Semantic。测试直接复用组件组合消融组，并与生产 service 对照；不增加生产配置或工具入口。原 `RetrievalQualityTest` 只有五个合成文件，按预期文件命中计算名为 recallAt5 的指标，不能代表真实库。

### 2.2 数据/状态模型

语料为当前工作树 `src/main/java` 下所有 Java 文件，保持仓库相对路径，复制至 target 下快照目录。不索引 docs、测试、评测问题或用户数据，防止答案泄漏。快照文件 SHA-256 清单与总摘要、数据集摘要、Git HEAD、模型空间、预算、计时参数进入报告。SQLite 和报告只写 target；报告不保存完整源码或返回片段正文，仅保存固定标注短语、位置及摘要。

每个问题包含 id、category、query、evidence 列表。每条证据由路径、源码 marker、requiredText 标识；marker 必须唯一存在于该文件。只有返回的正文同时包含 marker 和全部 requiredText 实现语句才算找到证据；仅文件名、签名或 class chunk 的大行号范围不能算命中。一个返回片段可以覆盖多条证据，重复片段不能重复增加证据召回或折扣覆盖分。关键语句证明有限的实现证据被返回，不证明完整答案成立。

### 2.3 核心时序与失败路径

```mermaid
flowchart LR
    S[生产 Java 代码快照与摘要] --> I[独立 SQLite 索引与真实本地 BGE]
    Q[固定问题与人工规则标注] --> V[校验路径与唯一 marker]
    I --> A[第一阶段五组 / 第二阶段三组召回消融]
    V --> A
    A --> F[现有 RRF 与字符预算]
    F --> M[逐问题指标与分类汇总]
    M --> R[JSON 明细与 Markdown 报告]
```

索引、向量或 stage 降级导致实验失败，禁止默默将降级结果当真实语义组。质量数值允许低于预期；质量测量与功能回归通过分开解释。

## 3. 方案设计

### 3.1 接口与数据结构

新增 test-only `RetrievalEvaluationMetrics`、其单测、数据集契约测试、显式启用的 `RepositoryRetrievalEvaluationTest` 及固定 JSON 问题集。通过 `-Drag.repository.eval=true` 启用重实验，日常 quick 不重建完整向量。

P@5 = 前五条中包含任一标注证据的片段数 / 5（短列表空位计零）；Recall@K = 找回的不同标注证据数 / 该问题标注证据总数；Hit@K = 是否找到任一证据；MRR@10 为首条相关片段的倒数排名。折扣证据覆盖分 = `sum(该排名首次找到的证据数 / log2(rank+1)) / 标注证据总数`，自然处于 [0,1]，这是自定义指标，**不是 nDCG**。未构建穷尽片段相关度 qrels，因此不报告标准 nDCG。无答案问题不进入正例指标，单独统计是否返回任何片段；返回片段不等同于模型幻觉。

这些是基于有限标注的指标：其他合理实现片段可能未被标注，P@5 是保守口径，Recall 是标注证据召回，不能称所有相关代码的穷尽召回。数据集由实现知情者构造，不是独立用户随机样本；同主题问法相互相关，不能直接推断线上表现。

TopK=10、maxChars=16000；P/Recall/Hit@5 使用同一 Top10 响应前缀，因此是 Top10 请求下的 prefix 指标。每组每题先预热一次，再测三次串行查询；质量指标使用第一次测量，计时统计所有三次。这是 `StageRunner -> Fusion -> Budget` 组件流水线时延，未含 service 锁、诊断构建、模型加载与索引耗时，不能称用户端到端时延；生产 service 对照在计时外。固定组顺序也限制严格性能比较。单独报告建库耗时。不调权重、不划出虚假的独立调参集；本次固定数据集建立基线，未来优化需新增隐藏测试集。

### 3.2 策略、安全、并发与恢复

仅本地模型和可提交生产代码；没有 Agent 工具、HITL 或 URL 权限变化。所有实验串行，各组独立执行真实召回，不复用预计算 query 向量来美化时延。工作目录与索引独立，复跑按源码 hash 增量回填。

### 3.3 兼容性、迁移与回滚

第一阶段生产行为不变，无迁移；第二阶段生产变更与回滚边界见第 3.4 节。首次运行可能较慢；报告保留机器/JDK 信息和模型空间，性能数字仅代表该机器串行暖查询。

### 3.4 第二阶段：删除 Graph 召回与词法修正（实现前评审）

用户已明确选择保留语义与关键词两路。比较过三种实现范围：仅删除 Graph 无法解决已复现的中文零召回；同时换模型及 embedding 输入会难以归因且扩大迁移范围；本次选择删除 Graph、修正查询词处理和候选预算的确定性缺口，保持模型、向量输入和权重不变。

目标及边界：

- 删除 `GraphRetriever`、其独立测试、`RetrievalSource.GRAPH` 及 RRF 图信号；默认只执行 Term FTS 和 Semantic。已有关系表/符号表及 `RepositoryMapSelector` 结构地图仍有调用者，不做破坏性 SQLite 迁移，也不修改 `/graph` 等独立命令。
- 保持文档 `normalize()` 及已有索引不变；增加查询专用规范化，中文先分词再过滤通用问句词，不把整段汉字串作为必需关键词，ASCII identifier/camelCase/snake_case 的形式继续保留。
- 查询先走现有 AND 精确交集；候选不足时按 OR 补充 BM25 结果，交集命中始终在前、按 chunkId 去重并限制数量。所有项仍在 SQLite 层逐词引用，用户输入不能注入 FTS 操作符。OR 可能增加噪声，这是明确的召回/精确率权衡，须通过无答案题和固定题集报告。
- 预算层跳过空正文候选，避免空结果中断后续有效候选；没有可保留完整行时按剩余字符预算截取正文，保持非空和总预算上限。不引入未经标注校准的 cosine 阈值。
- 模型和输入方案暂不调整：缺少路径/类上下文与向量平均目前属于待验证的质量因素，不等同于确定性实现错误。

```mermaid
flowchart LR
    Q[原始查询] --> N[查询专用分词与问句词过滤]
    N --> A[FTS AND + BM25]
    A --> B{候选足够?}
    B -->|否| O[FTS OR 补充并去重]
    B -->|是| F[两路 Weighted RRF]
    O --> F
    Q --> E[原本地 BGE + cosine]
    E --> F
    F --> C[跳过空正文 + TopK/字符预算]
```

依赖与文件：`LexicalTextNormalizer -> TermFtsRetriever -> SqliteRetrievalIndex`；`DefaultCodeRetrievalService -> StageRunner -> Fusion -> Budget`。更新对应单测、ToolRegistry 的来源格式测试、评测 runner、AGENTS/CODEAGENT/README/agents-reference；prompt 中只需核对已有统一工具契约，工具名与参数未改变，授权链无变化。

兼容性：SQLite schema 和已存向量不变，无 embedding backfill 迁移；删除 Graph 来源会改变诊断来源枚举，这是用户明确要求的运行时语义变化。历史报告仅作为文件保留，不回灌生产。第二阶段改动保持未提交，除非用户另行要求提交。

复测：保留 `before-fix-results.json` 与旧源码快照，固定原模型、语料和判定口径。删除 Graph 类后，原来询问该类的 3 道题不再适用于当前代码；仅移除这三题，不修改其余问题原文和证据。新题集为 69 道主集（63 正例、6 无答案）及原 6 个调用者定位探针，报告改用三个通道对照。前后结论只比较共同的 69 题，不将题集变化或源码变化伪装为质量提升。支持显式指定旧源码快照作固定语料。

验收：回归用例先失败再通过；诊断无 Graph；自然语言可在缺少问句词的文档中找回关键词交集/并集；精确交集不被宽松候选挤掉；空正文不阻塞；预算未越界；针对性测试、quick、固定语料复测与 diff 检查均执行。指标允许回退，按实际结果报告。

## 4. 实现任务与测试矩阵

### 4.1 实现前方案自审

已确认：只增加 test-only 组件和文档；依赖方向为测试 → 已有 RAG 组件，不引入生产反向依赖；源码快照隔离数据集防止泄漏；所有 stage 降级均使实验失败；生产全组与独立组装结果逐题比较；指标不设置人为及格线。Graph 当前以完整 query 匹配符号，因此主集自然语言及带类名的方法问法可能没有 Graph seed；每题记录 seed 数与实际 stage 候选数，并另设纯符号关系探针验证激活路径。探针不混入主集汇总。

### 4.2 任务与验证

- [x] 先写指标单测：多证据、重复、截断正文、短列表、空结果、无答案、排名截断；运行观察缺少指标实现导致编译失败，再实现。
- [x] 构造并校验 72 条固定用例：20 个主题各三种问法、6 个跨模块问题、6 个不存在能力问题。
- [x] 实现独立生产源码快照、真实建库、五组消融、生产路径一致性检查、逐题 JSON 和 Markdown 汇总。
- [x] 执行指标/数据集/真实评测针对性测试、现有 RAG 回归及 `mvn test -Pquick`。
- [x] 运行 `git diff`、`git diff --check`，同步本文件结果、面试口径和失败样例。

### 4.3 实施记录

分支从最新 origin/main 的 c60f41e 创建为 `test/rag-repository-evaluation`，不使用 worktree，不提交或推送。新增指标测试首次因缺少 `RetrievalEvaluationMetrics` 实现而失败；实现后 `mvn test -DskipTests=false "-Dtest=RetrievalEvaluationMetricsTest,RepositoryEvaluationDatasetTest"` 运行 6 项测试，0 失败、0 错误、0 跳过。

完整实验在 Windows PowerShell 下必须给含点的 Maven 参数加引号：`"-Drag.repository.eval=true"`。未加引号的初次命令被 shell 拆成生命周期参数，未运行实验；修正后建立 368 个 Java 文件、3,226 个 chunk 的独立索引。

只读评审发现签名命中可能掩盖正文截断、原排序覆盖分不应叫 nDCG、Graph 主集可能不激活。这些结论已核对生产源码与真实 SQLite 数据，修订为关键实现语句校验、自然有界的折扣证据覆盖分及独立探针。问题原文未按测量结果改写；修订的是标注判定口径，首轮结果保留在 `target/rag-evaluation/initial-results.json`，不能与最终口径直接混算。修订公式前两项指标单测按新期望失败，修订后 7 项指标/数据契约测试通过。报告增加内容摘要/字符数/marker 偏移，以及 `IN_PROGRESS/COMPLETE`、实际行数与完成模式，便于核查完整性且不复制源码正文。

### 4.4 最终实验结果（2026-10-06）

实验已完成 390 条逐题结果：78 题 × 5 组，每组每题预热一次、测量三次。主集仍为 72 题，其中 66 个正例用于质量汇总、6 个无答案问题单独统计；6 个关系探针另表统计。最后一次重跑质量指标与前一次修订后实验一致。

环境：Windows 11 amd64，Java 17.0.12，8 个可用逻辑处理器。本地 `bge-small-zh-v1.5-q`、512 维、artifact revision `1.18.0-beta28`。源码基线 `c60f41e8795258c0fc2ed18c443df94e0ab42647`，368 个 Java 文件、3,226 个 chunk。源码总摘要 `a55c320f0e9897b1115dcdde4e8ee798c59300c12f0716f42370a7c5acd1fe4c`；最终数据集摘要 `d8f32af0a6e1e91337955e9aa522aafe7adcb948c7563dda1a78dd4f96021f1f`。首轮从空库建索引约 92.53 秒；最终复跑复用向量，增量检查约 1.23 秒。不能将缓存检查时间称首次建库时间。

以下均为主集指标；P95 是最后一次单机串行暖组件流水线测量：

| 组别 | P@5 | 标注 Recall@5 | 标注 Recall@10 | Hit@5 | MRR@10 | 暖流水线 P95 |
|---|---:|---:|---:|---:|---:|---:|
| 词法 | 6.06% | 30.30% | 30.30% | 30.30% | 0.2121 | 1.80 ms |
| 语义 | 0.61% | 3.03% | 6.06% | 3.03% | 0.0246 | 75.10 ms |
| 词法 + 语义 | 6.36% | 31.82% | 33.33% | 31.82% | 0.2247 | 75.59 ms |
| 词法 + Graph | 6.06% | 30.30% | 30.30% | 30.30% | 0.2121 | 3.54 ms |
| 三路融合 | 6.36% | 31.82% | 33.33% | 31.82% | 0.2247 | 91.00 ms |

每个单证据问题通常只标注一个方法，在固定返回五条时，即使唯一相关方法排第一，P@5 也是 20%。未标注但合理的代码片段按零处理，因此这里的 P@5 是严格保守的标注片段精确率，不能当作“回答准确率 6.36%”。标注 Recall 和 Hit 更适合说明能否定位到目标实现。

三路融合分类：

| 分类 | 正例数 | Hit@5 | 标注 Recall@10 | 解释 |
|---|---:|---:|---:|---|
| 带类名的方法/精确符号 | 20 | 20/20 | 100% | Top-1 仅 10/20；类头或其他方法仍可能排在前面 |
| 中文语义问法 | 20 | 1/20 | 10% | Top-10 找回 2 个预期实现 |
| 中文改述问法 | 20 | 0/20 | 0% | 当前样本未找回标注实现 |
| 跨模块问题 | 6 | 0/6 | 0% | 未找回标注的成组实现证据 |

主集 72 题中 Graph 非空种子数为 0/72，Graph 候选非空数为 0/72，故不能用主集消融评价 Graph 被激活后的贡献。独立 6 个探针全部得到去重后的非空种子和 Graph 候选（6/6），完整融合找回调用者证据 4/6，但这些命中的 sources 都只有 FTS_TERMS，未观察到 Graph 提供有效实现证据的收益。

6 个无答案问题：包含语义的三组均返回片段 6/6，词法与词法+Graph 为 0/6。已核对 `SqliteRetrievalIndex.searchVector` 为所有可用向量按 cosine 排序并取 Top-K，没有相关度拒答阈值；这解释为何“有返回”不等于“有答案”。这里只测检索返回，未测模型是否会拒答或产生幻觉。

### 4.5 失败案例与源码解释

| 问题 | 标注目标 | 三路融合首条返回 |
|---|---|---|
| 请求发送前如何预测上下文 token 用量？ | ContextTokenTracker.predict | MemoryQueryTokenizer.java |
| 读写文件时如何拦住跑到仓库外面的路径？ | PathGuard.resolveSafe | McpClient.java |
| 检索结果如何按 TopK 和字符预算截断？ | RetrievalBudget.apply | LspDiagnosticFormatter.java |
| 多种检索结果如何合并排名并控制最后发给模型的文本大小？ | RetrievalFusion + RetrievalBudget | CenterPane.java |

已验证的源码行为：

- `SqliteRetrievalIndex.ftsMatch` 把所有规范化词用 AND 连接；主集中文长问句的 FTS 结果多为空，符号问法全部能定位。词法回归成功不代表原始自然语言查询能召回。
- `GraphRetriever` 把完整 query 用于 `searchSymbols`，主集未形成 seed；纯符号探针能形成 seed，但候选不一定带有效正文。
- 针对 PathGuard 的真实 SQLite 关系行显示 calls 的 from_symbol_id 落到 FILE 节点，而 FILE 节点没有正文 chunk。`CodeAnalyzer` 输出 `Class.method` 调用者，`IndexCoordinator.findSymbol` 的 key 来自带方法声明的 chunk 名；无法匹配时回退 fileSymbolId。`searchRelations` 又按 from_symbol_id 获取正文，因此出现关系存在但正文为空。此为当前已观察到的链路缺口，未在本评测任务修改生产实现。
- 语义向量全量遍历再排序，没有拒答阈值；真实本地模型在本题集中文问法到代码的相关排序较弱，这是测量结果。具体原因可能包含模型、输入拼接、分块与语料语言差异，尚未通过独立消融归因，不能只断言换模型即可解决。

第一阶段后续建议：优先验证查询关键词提取/词法匹配策略、Graph caller 到 symbol 的映射和正文回传，再用新隐藏测试集比较语义输入/模型及候选重排。用户随后选择删除 Graph，第二阶段只修复词法与预算问题，不再修复图召回。不得只根据本题集改权重然后宣称泛化收益。

### 4.6 运行命令与验证证据

在 Java 17 环境中从仓库根目录执行；Windows PowerShell 保留含点参数的引号：

```powershell
# 指标与数据集契约：最终 7 项通过
mvn test -DskipTests=false "-Dtest=RetrievalEvaluationMetricsTest,RepositoryEvaluationDatasetTest"

# 重实验及最终校验：8 项通过，390 行完整报告
mvn test -DskipTests=false "-Dtest=RepositoryRetrievalEvaluationTest,RetrievalEvaluationMetricsTest,RepositoryEvaluationDatasetTest" "-Drag.repository.eval=true"

# RAG/工具针对性回归（包含重实验）：74 项通过，无失败、错误或跳过
mvn test -DskipTests=false "-Dtest=RepositoryRetrievalEvaluationTest,RetrievalEvaluationMetricsTest,RepositoryEvaluationDatasetTest,RetrievalQualityTest,RetrievalFusionTest,SqliteRetrievalIndexTest,DefaultCodeRetrievalServiceTest,RetrieverStageIsolationTest,LexicalTextNormalizerTest,GraphRetrieverTest,SemanticRetrieverTest,CodeSearchServiceArchitectureTest,CodeSearchGoldenSetTest,ToolRegistryTest" "-Drag.repository.eval=true"

# 常规回归
mvn test -Pquick

git diff --check
```


本任务未运行全项目全量 suite 或 package：没有生产变更，已执行 RAG/工具针对性回归和所需 quick；不得称全项目测试全部通过。`git diff --check` 通过，新增文件另检查尾随空白与文件末尾换行。target 报告被 `.gitignore` 忽略，未提交 .env、secret 或会话数据。

结果文件：`target/rag-evaluation/results.json` 为含源码清单摘要、参数、逐题排名及证据命中信息的完整记录；`target/rag-evaluation/report.md` 为汇总。最后一轮日志 `verified-evaluation.log`，74 项回归日志 `final-evaluation.log`，quick 日志 `final-quick.log`，均在同一 target 目录。

### 4.7 面试口径

可以如实回答：“我建立了真实仓库生产 Java 语料的离线检索评测：368 个文件、3,226 个代码片段，72 道主集问题和 6 个独立关系探针，对比五组召回。当前三路融合的标注证据 Recall@5 为 31.8%、Recall@10 为 33.3%；带符号问题 Hit@5 为 100%，但中文改述和跨模块问题仍有明显缺口。测试还发现自然语言不能激活图种子、关系节点到正文映射和无答案返回控制需要改善。结果是固定题集上的检索指标，不是最终回答正确率；我没有用五文件合成样例的 100% 来代表真实质量。”

### 4.8 第二阶段实施与固定语料复测

已移除 Graph stage、来源枚举、RRF 图权重和独立 stage 测试；保留 `/graph`、关系表和结构地图。词法查询独立分词、过滤通用问句词，严格 AND 候选优先、不足时 OR 补充并按 chunkId 去重。索引文本与向量输入未改变，无需重建旧索引。预算跳过空正文，长单行允许使用剩余字符预算。

测试先行：最初 10 项边界测试中 5 项失败，分别覆盖 Graph 诊断、中文问句、并集补充、空正文和单行截断；修复后通过。只读评审发现 `A`/`Do` 和 `of()` 可能被停用词吞掉，分别新增用例复现失败后修正：带大写 ASCII 标识符和独立 ASCII 名称/无参调用形式保留。多词自然语言中的小写通用词继续过滤；这是查询意图的启发式识别，不能穷尽代码与自然语言歧义。新增超过 20 个 chunk 的交集/并集上限和去重检查。更新 AGENTS、CODEAGENT、README、agents-reference 及历史设计提示，未新增开发方案文档。

使用第一阶段源码快照固定语料：368 个 Java 文件、3,226 个 chunk，语料 SHA-256 仍为 `a55c320f0e9897b1115dcdde4e8ee798c59300c12f0716f42370a7c5acd1fe4c`。当前评测记录 `frozenCorpus=true`，语料摘要与当前检索实现摘要分别记录。旧 Graph 类仍留在实验快照中以保持语料完全一致，生产代码已删除。当前工作树源码不完全等同于这个快照，因此这些数字是受控比较，不能称当前部署语料的精确分数。

旧报告保留为 `before-fix-results.json` / `before-fix-report.md`。移除三个专门询问已删除 Graph 类的问题，其余共同问题的原文与 evidence 逐条相等；原六个关系探针改名为调用者定位探针并单列。新数据集 SHA-256 为 `bc2b39d219ae25dd34b06d6a4a3d75c4f20eb116a95d79fb07ef66ff9dfbd685`。三组共 225 行，以下前后数字均重新限定为共同 69 道主集，其中 63 道正例、6 道无答案；不是拿原 72 道总分直接比较。

| 检索组 | P@5 前 → 后 | Recall@5 前 → 后 | Recall@10 前 → 后 | MRR@10 前 → 后 |
|---|---:|---:|---:|---:|
| 关键词 | 6.03% → 7.30% | 30.16% → 36.51% | 30.16% → 36.51% | 0.2143 → 0.2513 |
| 语义 | 0.63% → 0.63% | 3.17% → 3.17% | 6.35% → 6.35% | 0.0258 → 0.0258 |
| 完整融合（原三路 → 当前双路） | 6.35% → 6.35% | 31.75% → 31.75% | 33.33% → 38.10% | 0.2275 → 0.2041 |

解释：关键词获得额外有效候选，但进入双路融合后部分原有命中被挤到第 6～10 位，首条相关结果的排序下降。双路 Recall@10 增加约 4.76 个百分点，Recall@5 与 P@5 未提升；语义结果完全不变，与模型及输入未改一致。改述和跨模块仍有明显缺口。六个调用者定位探针在关键词/双路下均 Hit@5 6/6，不混入主集分数。

代价：关键词无答案误返回从 0/6 变为 6/6；完整融合仍为 6/6。OR 只需任一通用业务词出现即可返回低相关候选，不能把“有返回”解释为“有答案”。本次没有按这六题硬调阈值或权重，也未声称语义模型是唯一原因。下一步应另建隐藏查询集验证候选相关度控制、融合排序与语义输入/模型，不能用本次题集上的收益代表线上泛化。

验证命令（均使用 Java 17；实际日志在 `target/rag-evaluation/`）：

```powershell
mvn test -DskipTests=false "-Dtest=RepositoryRetrievalEvaluationTest,RetrievalEvaluationMetricsTest,RepositoryEvaluationDatasetTest,RetrievalQualityTest,RetrievalFusionTest,RetrievalBudgetTest,SqliteRetrievalIndexTest,DefaultCodeRetrievalServiceTest,RetrieverStageIsolationTest,LexicalTextNormalizerTest,TermFtsRetrieverTest,SemanticRetrieverTest,CodeSearchServiceArchitectureTest,CodeSearchGoldenSetTest,ToolRegistryTest" "-Drag.repository.eval=true" "-Drag.repository.corpus=D:\IDEAworkSpace\zsxq\paicli\target\rag-evaluation\corpus-a55c320f0e9897b1115dcdde4e8ee798c59300c12f0716f42370a7c5acd1fe4c"
mvn test -Pquick
git diff --check
```

最终针对性与固定语料评测共 84 项通过，失败/错误/跳过均为 0（`fix-verified.log`）。225 行报告为 COMPLETE；逐条比较确认共同 69 题的 query/evidence 未变。索引缓存刷新 1,133 ms；当前检索源码摘要为 `f90a2aaf4051f22dea1ce36f7a78b793b36e2b7d14429923c03c9f2c9a49a23e`。只读复审发现的标识符与上限测试问题已闭环，最终复审无阻断问题。


第一阶段评测基线已按用户要求提交为 `8483ea4`。用户后续明确要求修改分支名、提交修改并发布分支，第二阶段生产修复、评测调整与文档同步统一在 `fix/rag-dual-retrieval` 提交并推送到 origin；提交标识以 Git 历史为准。未执行全项目全量 suite 或 package；固定语料比较与针对性回归覆盖本次检索边界。

### 4.9 当前回归验证

网络策略针对性测试：8 项通过。`mvn test -Pquick`：1,316 项，0 失败、0 错误、5 跳过，BUILD SUCCESS。生产代码与 RAG 评测数据未改变。


## 5. 验收清单

- [x] 无 mock Embedding、无原始 session/secret、无测试问题进入语料。
- [x] 所有 marker 与关键语句经源码契约校验，报告可复跑，原始逐题结果在 target。
- [x] 质量问题如实报告，未修改排序使本数据集过拟合。
- [x] 第一阶段评测基线已提交；第二阶段按用户后续授权提交并发布分支。验证按阶段记录。
