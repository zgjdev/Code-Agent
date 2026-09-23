# 自动执行模式路由实现计划

> **执行要求：** 当前会话使用 `executing-plans` 按任务顺序实施；每个生产代码边界先运行失败测试，再写最小实现。未经用户明确允许不提交、不推送。

**目标：** 在默认 `Main` inline/plain 终端路径中，让普通顶层输入始终由无工具 Mode Router 选择 ReAct 或 Plan，并保留 `/plan`、`/react` 单轮强制覆盖。

**架构：** Router 读取 `ParentConversationContext.conversationNodes()` 的确定性窗口和原始 `submittedInput`，使用当前活动 `LlmClient` 完成严格 JSON 二分类。Main 在同一个取消域内完成路由和实际 Agent 调度，实际执行仍完整位于 `SnapshotService.runTurn()` 内。

**技术栈：** Java 17、JUnit 5、Jackson、Maven、现有 `LlmClient`、`ConversationLedger`、`ParentConversationContext`。

## 全局约束

- 自动路由始终开启，不增加环境变量、系统属性或持久化配置开关。
- Router 不注册工具、不获得权限、不读取展开后的 `taskInput`、不写 Parent Session。
- 非取消性失败回退 ReAct；取消或中断终止整个 Turn。
- Lanterna TUI、Runtime API、WeChat 和 Plan 恢复命令不接入 Router。
- 不改变 Session、checkpoint 或 Plan SQLite schema。

---

### Task 1：模式模型、语义窗口与共享 formatter

**文件：**
- Create: `src/main/java/com/codeagent/agent/ExecutionMode.java`
- Create: `src/main/java/com/codeagent/agent/RoutingSource.java`
- Create: `src/main/java/com/codeagent/agent/RoutingDecision.java`
- Create: `src/main/java/com/codeagent/agent/ExecutionModeRoutingContext.java`
- Create: `src/main/java/com/codeagent/history/TopLevelConversationFormatter.java`
- Modify: `src/main/java/com/codeagent/plan/PlannerConversationContextBuilder.java`
- Test: `src/test/java/com/codeagent/agent/ExecutionModeRoutingContextTest.java`
- Test: `src/test/java/com/codeagent/history/TopLevelConversationFormatterTest.java`
- Modify: `src/test/java/com/codeagent/plan/PlannerTest.java`

**接口：**
- `ExecutionModeRoutingContext.select(List<ConversationNode>) -> List<ConversationNode>`：最新 SUMMARY 加最近最多三个 USER turn。
- `TopLevelConversationFormatter.format(List<ConversationNode>) -> String`：稳定输出 `[User]`、`[Assistant]`、`[Summary]` 标签。
- `RoutingDecision(ExecutionMode, RoutingSource, Optional<MeasuredUsage>)`。

- [x] 写窗口裁剪、未闭合 turn、SUMMARY 下界和 formatter 输出测试。
- [x] 运行 `mvn test -DskipTests=false -Dtest=ExecutionModeRoutingContextTest,TopLevelConversationFormatterTest`，确认因类型缺失失败。
- [x] 添加最小类型和窗口/formatter 实现，让 Planner builder 委托 formatter。
- [x] 运行上述测试及 `PlannerTest`，确认通过。

### Task 2：两消息 Prompt 与严格 Router

**文件：**
- Create: `src/main/java/com/codeagent/prompt/ModeRouterPromptBuilder.java`
- Create: `src/main/resources/prompts/modes/router.md`
- Create: `src/main/java/com/codeagent/agent/ExecutionModeRouter.java`
- Test: `src/test/java/com/codeagent/prompt/ModeRouterPromptBuilderTest.java`
- Test: `src/test/java/com/codeagent/agent/ExecutionModeRouterTest.java`

**接口：**
- `ModeRouterPromptBuilder.build(List<ConversationNode>, String) -> List<LlmClient.Message>`：严格返回 system + user 两条消息；user content 是含 `conversationContext`、`submittedInput` 的 JSON。
- `ExecutionModeRouter.route(String, List<ConversationNode>) -> RoutingDecision`：调用 `llmClient.chat(messages, null)` 并严格解析单字段 JSON。

- [x] 写 system/user 隔离、JSON 可逆、伪造角色标签仍为 user 数据的失败测试。
- [x] 运行 `ModeRouterPromptBuilderTest`，确认因实现缺失失败；添加最小 Prompt 实现并跑绿。
- [x] 写合法响应、所有非法响应、tools 为 null、usage 和取消传播测试。
- [x] 运行 `ExecutionModeRouterTest`，确认因实现缺失失败；添加最小 Router 实现并跑绿。

### Task 3：CLI `/react` 单轮覆盖

**文件：**
- Modify: `src/main/java/com/codeagent/cli/CliCommandParser.java`
- Modify: `src/main/java/com/codeagent/cli/CodeAgentCompleter.java`
- Modify: `src/test/java/com/codeagent/cli/CliCommandParserTest.java`
- Modify: `src/test/java/com/codeagent/cli/CodeAgentCompleterTest.java`

**接口：**
- 新增 `CommandType.SWITCH_REACT`。
- `/react` 返回空 payload；`/react <task>` 返回裁剪后的任务 payload。

- [x] 先添加解析、未知相似命令和补全测试并运行，确认失败。
- [x] 添加最小 parser/completer 实现。
- [x] 运行 `mvn test -DskipTests=false -Dtest=CliCommandParserTest,CodeAgentCompleterTest`，确认通过。

### Task 4：Main 调度、取消、snapshot 与 ledger 接线

**文件：**
- Modify: `src/main/java/com/codeagent/cli/Main.java`
- Create: `src/test/java/com/codeagent/cli/MainExecutionModeRoutingTest.java`
- Modify: `src/test/java/com/codeagent/cli/MainInputNormalizationTest.java`
- Modify: `src/test/java/com/codeagent/cli/MainPlanAgentFactoryTest.java`

**接口：**
- `ExecutionMode nextTaskOverride` 替换 `boolean nextTaskUsePlanMode`。
- 普通输入在 `runWithCancelSupport` 内先 route；显式 override 直接产生 `RoutingDecision(EXPLICIT)`。
- 路由完成后调用 `ConversationLedger.appendEvent("execution_mode_selected", ...)`；metadata 不含 prompt、历史或用户正文。
- 只有真实 Agent 调用进入 `SnapshotService.runTurn(finalMode, taskInput, ...)`。

- [x] 先添加普通输入调用 Router、显式命令绕过 Router、fallback、取消、active client、snapshot 边界和 ledger metadata 测试并确认失败。
- [x] 最小修改 Main 调度与提示文案，确保 `/plan resume`、`/plan abandon` 继续绕过 Router。
- [x] 运行 `mvn test -DskipTests=false -Dtest=MainExecutionModeRoutingTest,MainInputNormalizationTest,MainPlanAgentFactoryTest`，确认通过。

### Task 5：文档同步与回归验证

**文件：**
- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `docs/agents-reference.md`
- Modify: `docs/dev/15-auto-execution-mode-routing.md`

- [x] 将设计状态改为已实现，并同步普通输入自动路由、`/plan`/`/react` 单轮覆盖及 Lanterna 不在范围内。
- [x] 运行设计文档列出的三组针对性测试（166 项通过，2 项跳过）。
- [x] 运行 `mvn test -Pquick`（1014 项中 7 项 Windows 平台现存的非 Router 用例失败，本功能测试通过）。
- [x] 运行 `mvn test -DskipTests=false`（1063 项中 11 项 Windows 平台现存的非 Router 用例失败，本功能测试通过）。
- [x] 运行 `mvn clean package`（IDE 占用 `target/classes` 导致 clean 失败；随后 `mvn package -DskipTests` 成功生成 JAR）。
- [x] 运行 `git diff --check` 并审查 `git diff`，确认没有无关改动和敏感内容。

## 计划自检

- 设计中的模式模型、上下文窗口、Prompt 边界、严格解析、取消、CLI、Main、ledger、snapshot 和文档均有对应任务。
- 类型名与 `docs/dev/15-auto-execution-mode-routing.md` 一致。
- 未包含配置开关、独立 Router provider、运行中迁移或范围外入口。
