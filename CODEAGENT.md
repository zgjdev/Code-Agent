# CODEAGENT.md

## Commands

- 构建：`mvn clean package` 默认跳过测试，优先产出可手工验收 jar。
- 常规回归：`mvn test -Pquick`；TUI 相关跑 `mvn test -Pphase16-smoke`。
- 针对性测试：`mvn test -Dtest=XxxTest -DskipTests=false`。

## What This Is

CodeAgent 是面向商业使用的 Java Agent CLI 产品，对标 Claude Code；默认 inline/plain 顶层任务由 Mode Router 自动选择 ReAct 或统一的多 Agent 协作 Plan-and-Execute，`/react` 与 `/plan` 提供单轮显式覆盖。

## Architecture

- 两条执行路径共享 `ToolRegistry` / `MemoryManager` / `SnapshotService`，不要为某个模式创建孤立能力。
- 精确代码定位优先 `glob_files` / `grep_code` / `read_file`；`search_code` 只组合 SQLite FTS5 + BM25、进程内 Qwen3 FP32 1024维 + cosine 两类召回，默认Top10/16000正文字符。语义query保留行为需求，lexical_query是软线索，混合问句也可提取代码名称；不得把grep塞回RAG。候选文件hash诊断不证明全库时效，关键判断/修改前read_file核实当前源码；空索引或失效回到实时定位，不自动全库回填。Graph不参与RAG融合，符号/关系索引继续服务结构地图与`/graph`。Qwen权重需显式预装，故障保留FTS；长期Memory仍BGE，远程Embedding必须有当前项目显式授权。
- system prompt 由 `PromptAssembler` 分层组装，内置 prompt 在 `src/main/resources/prompts/`，支持 `~/.codeagent/prompts/` 和 `.codeagent/prompts/` 覆盖。

## Things That Will Bite You

- 交互式 CLI 默认进程内自动维护代码索引（启动对账、监听去抖、周期校准，词法先更新/向量补齐）；`autoIndex.enabled=false` 关闭后台维护，仅查询已有索引。检索自身不全库回填。Runtime API/headless 不隐式创建维护线程，由宿主显式调用底层刷新接口；后台服务不占 Session lease，不自动授权远程 Embedding。

- 改行为要同步 `AGENTS.md` / `README.md` / `ROADMAP.md`；路线图只在状态变化时更新。
- 改命令入口要联动 `Main.java`、`CliCommandParser.java`、测试、`README.md`、`AGENTS.md`。
- 改工具集要联动 `ToolRegistry.java`、Agent/Plan/SubAgent 提示词和文档。
- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取临时事实。
- `ctx` 表示下一轮仍会带入请求的上下文估算；`in/out/cache` 表示最近任务 LLM 调用统计，不要混用。

## Don't

- 不提交 `.env`、真实 API Key、`target/` 产物。
- 不把 `ROADMAP.md` 的未来计划写成已交付能力。
- 不在交互主路径新增裸 `System.out.println`；优先走 `Renderer.stream()`。
