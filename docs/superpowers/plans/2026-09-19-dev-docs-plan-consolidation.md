# 开发文档 Plan 统一与一致性修订 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除独立 02 文档，将 Plan-and-Execute 全部收敛到 03，并修正 01、03–06 与当前源码、测试不一致的表述和证据。

**Architecture:** 以当前 Java 源码和可复现测试结果为唯一事实来源，按“Plan 主线、RAG 测试证据、Runtime 术语、Memory 协作边界”四组做局部修订。保留现有长文结构，不重写无关章节；通过关键词扫描、文件引用扫描和差异检查防止旧称谓残留。

**Tech Stack:** Markdown、Mermaid、PowerShell、Git、Maven/JUnit 5、Java 17。

## Global Constraints

- 不修改 `src/`、`pom.xml`、`AGENTS.md`、`README.md` 或运行时行为。
- `docs/dev/02-dag-orchestration.md` 保持删除，不创建占位文件。
- `docs/dev/12-unified-multi-agent-plan-and-execute.md` 的已完成迁移内容由文档 03 承载，删除这份过期实施方案。
- 已删除类型可以作为历史背景出现，但不得作为当前实现证据。
- 保留用户在 `docs/dev/01-react-agent.md` 和 `docs/dev/03-multi-agent-collaboration.md` 中已有的未提交修改。
- 不将当前目标文档加入新提交；工作区已有改动的归属保持不变。
- 所有写入使用 `apply_patch`，验证使用 JDK 17。

---

### Task 1: 固化 02 删除与 Plan 文档归属

**Files:**
- Delete: `docs/dev/02-dag-orchestration.md`（确认已有删除状态）
- Modify: `docs/dev/01-react-agent.md`
- Modify: `docs/dev/03-multi-agent-collaboration.md`

**Interfaces:**
- Consumes: `Planner`、`ExecutionPlan`、`PlanExecuteAgent`、`PipelineOptions.FULL_PRESET` 的当前行为。
- Produces: 唯一的 Plan-and-Execute 文档入口 `docs/dev/03-multi-agent-collaboration.md`。

- [ ] **Step 1: 确认 02 只处于删除状态**

Run:

```powershell
git status --short -- docs/dev/02-dag-orchestration.md
Test-Path docs/dev/02-dag-orchestration.md
```

Expected: Git 显示 `D  docs/dev/02-dag-orchestration.md`，`Test-Path` 返回 `False`。

- [ ] **Step 2: 扫描仍把 02 当有效链接的文本**

Run:

```powershell
Select-String -Encoding UTF8 -Path docs/dev/*.md -Pattern '\]\([^)]*02-dag-orchestration\.md\)|见 `02-dag-orchestration\.md`'
```

Expected: 无有效链接；03 中允许以反引号纯文本说明“原 02 已删除”。

- [ ] **Step 3: 修订 01 的当前实现引用**

在 `01-react-agent.md` 中：

- Plan 交叉引用统一指向 `03-multi-agent-collaboration.md`；
- 删除把 `AgentOrchestrator` 当作当前 browser lease 释放方或 ledger 写入方的证据；
- 当前 Plan 证据只保留 `PlanExecuteAgent`，Reviewer 证据只保留 `SubAgent`；
- 将失效的 `AgentBudget.java:205`、`ToolRegistry.java:1582` 等引用改到对应当前符号，优先引用方法名而非脆弱的末尾行号。

- [ ] **Step 4: 修正 03 的 URL 权限矛盾**

将“补充要求只会扩大授权集合，不会撤销原有授权”替换为以下语义：

```markdown
补充要求会基于累计的顶层用户原文重建策略，因此既可能增加授权，也可能收紧授权：新增 URL 或明确联网动作可开放相应能力；“不需要联网”等显式约束会令 `explicitNoWeb/webForbidden` 生效，即使原目标曾要求联网，也会隐藏并拒绝 Web 工具。两条方向分别由 `supplementRebuildsToolPolicyBeforeReplanning` 与 `noWebSupplementTightensToolPolicyBeforeReplanning` 覆盖。
```

- [ ] **Step 5: 收紧 03 的 child session 表述**

所有“每任务 child session 审计”改为“注入 `parentSession` 时创建任务级 child session；当前 CLI 注入，TUI 不注入”。历史 `/team`、`AgentOrchestrator`、`ExecutionStep`、`StepStatus` 只保留在合并背景或删除说明中。

- [ ] **Step 6: 验证 Plan 事实与测试**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn test "-Dtest=ExecutionPlanTest,PlannerTest,PlanExecuteAgentTest,StepBriefingTest,ReviewResponseParserTest,SubAgentStepReviewerTest,PipelineOptionsTest,MainPlanAgentFactoryTest" -DskipTests=false
```

Expected: 相关测试全部通过。

---

### Task 2: 修订 RAG 文档的测试证据与失效引用

**Files:**
- Modify: `docs/dev/04-code-rag-graph.md`

**Interfaces:**
- Consumes: `CodeIndex`、`CodeRetriever`、`VectorStore` 与当前 Windows 测试结果。
- Produces: 区分“功能已实现”和“测试在当前环境可稳定通过”的 RAG 证据。

- [ ] **Step 1: 修正测试覆盖表**

在 `CodeIndexTest` 条目中明确：测试使用默认 `EmbeddingClient`，没有注入 fake，依赖本机 Ollama/配置的 Embedding 服务；服务不可用时文件级异常被吞并并返回 0 个块，因此它不是纯离线稳定单测。

在 `CodeRetrieverTest` 条目中明确：虽然使用 stub Embedding，但测试夹具先用原始 `/tmp/...` 构造 `VectorStore`，被测 `CodeRetriever` 会规范化成 Windows 绝对路径，Windows 上项目键不一致导致空结果；生产的 `CodeIndex` 与 `CodeRetriever` 两端都会规范化项目路径。

- [ ] **Step 2: 更新失效源码行号**

修正超出文件末尾或已偏离符号的引用：`CodeChunk.java:42-43`、`CodeIndex.java:176`、`CodeRetriever.java:163`。引用目标分别改为 `CodeChunk.toEmbeddingText`、`CodeIndex.IndexResult`、`CodeRetriever.close` 的当前位置或符号名。

- [ ] **Step 3: 限定“均已接线并可工作”**

改为：CLI 与工具入口均已接线；语义索引和混合检索需要可用的 Embedding provider，服务不可用时不会自动退化为完整关键词索引流程。

- [ ] **Step 4: 验证 RAG 测试证据陈述**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn test "-Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest,CodeRetrieverTest,SearchResultFormatterTest,EmbeddingClientTest" -DskipTests=false
```

Expected on current Windows environment: `CodeIndexTest` 的两个 Embedding 相关用例和 `CodeRetrieverTest` 的路径夹具用例失败；文档必须如实记录，不能写成全绿。

---

### Task 3: 统一 Runtime 文档中的执行模式术语

**Files:**
- Modify: `docs/dev/05-runtime-api-tasks.md`

**Interfaces:**
- Consumes: 当前只有 ReAct 与统一 `/plan` 两条主路径的架构事实。
- Produces: 不再暗示独立 Team 模式仍存在的 Runtime 说明。

- [ ] **Step 1: 替换当前能力中的 `Plan/Team`**

把“无头路径不能跑 Plan 或 Team”“三种执行模式”等当前时态表述统一改成：

```markdown
无头路径只构造普通 `Agent`，因此只执行 ReAct；统一 `/plan` 依赖计划门、DAG 调度和步骤 Reviewer，目前不在无头入口中接线。
```

历史背景中如需提到 Team，必须明确“已删除的独立 `/team` 模式”。

- [ ] **Step 2: 更新 `Main.java` 接线引用**

以当前 `runHeadlessTask`、`openTaskManager`、`serve` 早返回和 Plan 工厂方法的符号位置替换旧的 `Main.java:1142-1161` 等漂移引用。

- [ ] **Step 3: 保留 Runtime 三条关键限定**

确认简历证据仍明确：HTTP Turn 不走 durable queue；SSE 是一次性事件快照配合客户端轮询；无头 ReAct 不经过 HITL。

- [ ] **Step 4: 验证 Runtime 主干**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn test "-Dtest=DurableTaskManagerTest,RuntimeApiServerTest" -DskipTests=false
```

Expected: 全部通过。

---

### Task 4: 修订 Memory 文档的 Plan/Reviewer 边界

**Files:**
- Modify: `docs/dev/06-memory-context.md`

**Interfaces:**
- Consumes: `Agent`、`PlanExecuteAgent.executeTaskWithPolicy`、Reviewer `SubAgent` 与共享 `ToolRegistry`。
- Produces: 当前统一 Plan 架构下准确的长期记忆读取/写入说明。

- [ ] **Step 1: 替换旧 Team/Worker 叙述**

将“Team worker 能写、读不到”改为：

```markdown
统一 Plan 的任务执行体由 `PlanExecuteAgent.executeTaskWithPolicy` 承载，会按任务描述调用 `memoryManager.buildContextForQuery`，因此可读取相关长期记忆；它与主 Agent 共用 ToolRegistry 的 memory saver，也可写入。步骤 Reviewer 使用独立 `SubAgent`，其 prompt 只加载 `CODEAGENT.md`，不主动检索长期记忆，但仍能通过共享 ToolRegistry 调用 `save_memory`。
```

- [ ] **Step 2: 清除当前证据中的 `AgentOrchestrator`**

修订第 7、9、13–16 部分的重复表述；当前 saver 接线引用 `Agent.java:94` 与 `PlanExecuteAgent.java:189`，Plan 读取引用 `PlanExecuteAgent.java:645-652`，Reviewer 不读取引用 `SubAgent` 的 prompt 构建路径。

- [ ] **Step 3: 更新 Memory 测试边界**

在 `MemoryManagerTest` 证据旁注明：`shouldStoreProjectScopedFactsByDefault` 的 `endsWith("/repo/current")` 在 Windows 上因 `\` 分隔符失败；scope 和项目隔离实现仍由其他断言及源码支撑，但当前测试不是跨平台的。

- [ ] **Step 4: 更新失效引用**

修正 `ConversationHistoryCompactor.java:188`、`MemoryRetriever.java:113`、`TokenBudget.java:184` 等越界引用，改为对应当前方法或 record 的符号位置。

- [ ] **Step 5: 验证 Memory/Session 主干**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn test "-Dtest=MemoryManagerTest,MemoryRetrieverTest,ConversationHistoryCompactorTest,AutoCompactionManagerTest,SessionMemoryCompactorTest,ContextProfileTest,ContextTokenTrackerTest,SessionStoreTest,SessionReplayerTest,SessionCompactionRecoveryTest" -DskipTests=false
```

Expected on current Windows environment: 除 `MemoryManagerTest.shouldStoreProjectScopedFactsByDefault` 的路径分隔符断言外，其余通过；文档如实记录该边界。

---

### Task 5: 全文一致性与交付验证

**Files:**
- Verify: `docs/dev/01-react-agent.md`
- Verify: `docs/dev/03-multi-agent-collaboration.md`
- Verify: `docs/dev/04-code-rag-graph.md`
- Verify: `docs/dev/05-runtime-api-tasks.md`
- Verify: `docs/dev/06-memory-context.md`

**Interfaces:**
- Consumes: Tasks 1–4 的修订结果。
- Produces: 可交付的统一文档集合和真实验证报告。

- [ ] **Step 1: 扫描有效链接和当前时态残留**

Run:

```powershell
Select-String -Encoding UTF8 -Path docs/dev/01-react-agent.md,docs/dev/03-multi-agent-collaboration.md,docs/dev/04-code-rag-graph.md,docs/dev/05-runtime-api-tasks.md,docs/dev/06-memory-context.md -Pattern '\]\([^)]*02-dag-orchestration\.md\)|AgentOrchestrator\.java:|无头.*Plan/Team|三种执行模式|只会扩大.*授权|Team 的 SubAgent 能写'
```

Expected: 无匹配；03 开头允许不带链接/行号的历史类名说明，因此若扫描命中必须逐条判断是否明确标注“已删除”。

- [ ] **Step 2: 扫描越过文件末尾的 Java 行号引用**

使用 PowerShell 提取 `File.java:line`，按 basename 映射到 `src/main`/`src/test`，确保引用行号不超过对应文件总行数。Expected: 0 个越界引用。

- [ ] **Step 3: 检查 Markdown 与差异**

Run:

```powershell
git diff --check
git diff --stat
git diff -- docs/dev/01-react-agent.md docs/dev/03-multi-agent-collaboration.md docs/dev/04-code-rag-graph.md docs/dev/05-runtime-api-tasks.md docs/dev/06-memory-context.md
git status --short --branch
```

Expected: `git diff --check` 通过；02 保持删除；只出现本计划范围内的 Markdown 修改及用户原有工作区变化。

- [ ] **Step 4: 交付真实结果**

最终回复列出：各文档修正摘要、02 删除状态、实际测试命令与通过/失败数量、仍存在的测试夹具限制、未修改 Java 源码，并明确未提交用户已有的目标文档改动。
