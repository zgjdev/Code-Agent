# 自动模式路由内容一致性修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清除当前有效内容中“默认 ReAct”和“三套独立执行模式”的陈旧表述，使 prompt、CLI 文案、生成模板和产品文档与自动路由架构一致。

**Architecture:** 不修改执行逻辑；以 `ExecutionModeRouter` 和 `Main` 当前接线为事实来源，先用测试锁定运行时文案与生成模板，再修改内置资源和当前文档。历史方案只在自称当前状态时修正。

**Tech Stack:** Java 17、JUnit 5、Maven、Markdown、Mermaid。

## Global Constraints

- 普通 inline/plain 顶层任务由 Mode Router 自动选择 ReAct 或统一的多 Agent 协作 Plan-and-Execute。
- `/react` 与 `/plan` 仅覆盖当前轮；非取消性路由失败回退 ReAct。
- Lanterna TUI、Runtime API 和 WeChat 不宣称已接入自动路由。
- Planner、Task Worker、Reviewer 是 Plan 路径内部角色，不是第三套顶层执行模式。
- 不修改路由、授权、并发、持久化或恢复逻辑。
- 不执行 `git commit`、`git push`、创建 PR 或合并。

---

### Task 1: 用回归测试锁定当前架构文案

**Files:**
- Modify: `src/test/java/com/codeagent/prompt/PromptAssemblerTest.java`
- Modify: `src/test/java/com/codeagent/cli/MainInputNormalizationTest.java`
- Modify: `src/test/java/com/codeagent/cli/ProjectMemoryInitializerTest.java`

**Interfaces:**
- Consumes: `PromptAssembler.assemble(PromptMode.AGENT, ...)`、`Main.startupHints()`、`ProjectMemoryInitializer.initialize(...)`。
- Produces: 对 ReAct prompt、启动提示和 `/init` 模板的架构一致性断言。

- [x] **Step 1: 编写失败断言**

在 `PromptAssemblerTest.assemblesBuiltinPromptWithDynamicSections()` 中断言 prompt 说明当前轮正在使用 ReAct，并覆盖自动路由、`/react` 显式指定和未接入自动路由的直接 ReAct 入口；同时断言不包含“默认 ReAct 模式”。

在 `MainInputNormalizationTest.startupHintsKeepSlashCommandDetailsOutOfInitialScreen()` 中断言 hints 包含“普通任务自动选择 ReAct 或 Plan-and-Execute”，且不包含“默认模式是 ReAct”。

在 `ProjectMemoryInitializerTest.generatesConciseCodeAgentProjectMemory()` 中断言生成内容包含“Mode Router”和“两条执行路径”，且不包含“三套执行模式”和“三条执行路径”。

- [x] **Step 2: 运行测试并确认 RED**

Run:

```powershell
mvn test -DskipTests=false "-Dtest=PromptAssemblerTest,MainInputNormalizationTest,ProjectMemoryInitializerTest"
```

Expected: 以上新增断言因现有陈旧文案失败，测试可以正常编译和运行。

### Task 2: 修复运行时 prompt、启动提示和 `/init` 模板

**Files:**
- Modify: `src/main/resources/prompts/modes/agent.md`
- Modify: `src/main/java/com/codeagent/cli/Main.java`
- Modify: `src/main/java/com/codeagent/cli/ProjectMemoryInitializer.java`
- Modify: `src/main/java/com/codeagent/prompt/PromptAssembler.java`

**Interfaces:**
- Consumes: Task 1 的断言。
- Produces: 准确的内置 ReAct 身份说明、启动提示和项目记忆模板。

- [x] **Step 1: 最小修改实现**

将 `agent.md` 首段改为说明当前轮正在使用 ReAct，并列明自动路由、`/react` 覆盖和直接 ReAct 入口；将启动提示改为普通任务自动选择两条路径；将 CodeAgent 专用 `/init` 模板改为自动路由 + 两条路径，并说明 Plan 路径内部包含多 Agent 协作。将 `stripToolSections()` 的段落边界改为兼容 LF/CRLF。

- [x] **Step 2: 运行测试并确认 GREEN**

Run:

```powershell
mvn test -DskipTests=false "-Dtest=PromptAssemblerTest,MainInputNormalizationTest,ProjectMemoryInitializerTest"
```

Expected: `BUILD SUCCESS`，三个测试类全部通过。

### Task 3: 修复当前有效文档和内置 Skill

**Files:**
- Modify: `CODEAGENT.md`
- Modify: `README.md`
- Modify: `docs/agents-reference.md`
- Modify: `src/main/resources/skills/web-access/SKILL.md`
- Modify as evidence requires: `ROADMAP.md`
- Modify as evidence requires: `docs/phase-*.md`
- Modify as evidence requires: `src/main/java/com/codeagent/tui/pane/StatusPane.java`
- Modify as evidence requires: `src/test/resources/code-search/golden-set.json`

**Interfaces:**
- Consumes: `AGENTS.md`、`ExecutionModeRouter`、`Main`、`PlanExecuteAgent` 的当前行为。
- Produces: 与两条顶层路径一致的当前产品说明，同时保留明确的历史阶段语境。

- [x] **Step 1: 逐项分类搜索结果**

搜索“默认 ReAct”“三套执行模式”“三条执行路径”“ReAct / Plan / Team”“普通输入走 ReAct”。对每一项判断是当前说明、运行时代码注释还是历史阶段记录；只修改前两类和错误自称“当前状态”的历史文档。

- [x] **Step 2: 更新当前说明**

统一术语为“ReAct 与统一的多 Agent 协作 Plan-and-Execute 两条执行路径”。涉及策略时用“ReAct 与 Plan 的各执行分支”；涉及 prompt 时区分 ReAct Agent、Plan task executor、Planner、Reviewer 等内部角色。

- [x] **Step 3: 复查历史边界**

确认旧 phase/dev 文档中的前置现状、旧代码片段和里程碑叙述仍保留历史含义；任何保留的旧术语不得继续声称是当前架构。

### Task 4: 回归、内容审计和交付检查

**Files:**
- Modify: `docs/dev/21-mode-routing-content-consistency.md`（勾选验收项）
- Modify: `docs/dev/22-mode-routing-content-consistency-implementation-plan.md`（勾选完成步骤）

**Interfaces:**
- Consumes: Tasks 1-3 的全部变更。
- Produces: 可复核的测试、内容审计和 diff 证据。

- [x] **Step 1: 运行针对性测试**

```powershell
mvn test -DskipTests=false "-Dtest=PromptAssemblerTest,MainInputNormalizationTest,ProjectMemoryInitializerTest,ExecutionModeRouterTest,MainExecutionModeRoutingTest,ModeRouterPromptBuilderTest"
```

Expected: `BUILD SUCCESS`。

- [x] **Step 2: 运行快速回归**

```powershell
mvn test -Pquick
```

Expected: `BUILD SUCCESS`；若出现失败，记录是否与本次变更相关并按系统化调试流程处理。

- [x] **Step 3: 运行全量测试**

```powershell
mvn test -DskipTests=false
```

Expected: `BUILD SUCCESS`；若仓库存在已知平台失败，记录具体测试和证据，不虚报通过。

- [x] **Step 4: 审计陈旧表述与 diff**

使用递归文本搜索复核所有候选词，人工区分保留的历史上下文。然后运行：

```powershell
git diff --check
git status --short
git diff --stat
```

Expected: `git diff --check` 无输出；只有本计划范围内文件发生变化。
