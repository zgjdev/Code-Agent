# Mode Router 中文提示词实施计划

**目标：** 将 `prompts/modes/router.md` 的说明文字等价改为中文，同时保持输入字段、模式标识和严格 JSON 输出协议不变。

**方案：** 不修改 Router Java 实现。先调整现有 Prompt Builder 测试，要求 system prompt 包含中文职责说明并继续包含 `react`、`plan` 协议值；确认测试因英文提示词失败后，再翻译 prompt 并复跑 Router 相关测试。

**技术栈：** Java 17、JUnit 5、Maven、Markdown prompt resource。

## 全局约束

- `conversationContext`、`submittedInput` 字段名保持不变。
- `{"mode":"react"}`、`{"mode":"plan"}` 输出保持不变。
- ReAct/Plan 分类条件、失败回退和取消语义保持不变。
- 不新增配置项，不修改 Java 生产代码。

## Task 1：中文化 Router prompt

**文件：**

- Modify: `src/test/java/com/codeagent/prompt/ModeRouterPromptBuilderTest.java`
- Modify: `src/main/resources/prompts/modes/router.md`

- [x] 将测试改为断言 system prompt 包含“执行模式路由器”，并继续包含 `react`、`plan`。
- [x] 运行 `mvn test -DskipTests=false -Dtest=ModeRouterPromptBuilderTest`，确认因当前英文 prompt 缺少中文职责说明而失败。
- [x] 将 `router.md` 等价翻译为中文，保留字段名、模式值和 JSON 示例。
- [x] 运行 `ModeRouterPromptBuilderTest,ExecutionModeRouterTest`，确认全部通过。
- [x] 运行 `git diff --check` 并检查最终 diff。
