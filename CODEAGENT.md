# CODEAGENT.md

## Commands

- 构建：`mvn clean package` 默认跳过测试，优先产出可手工验收 jar。
- 常规回归：`mvn test -Pquick`；TUI 相关跑 `mvn test -Pphase16-smoke`。
- 针对性测试：`mvn test -Dtest=XxxTest -DskipTests=false`。

## What This Is

CodeAgent 是面向商业使用的 Java Agent CLI 产品，对标 Claude Code；默认 inline/plain 顶层任务由 Mode Router 自动选择 ReAct 或统一的多 Agent 协作 Plan-and-Execute，`/react` 与 `/plan` 提供单轮显式覆盖。

## Architecture

- 两条执行路径共享 `ToolRegistry` / `MemoryManager` / `SnapshotService`，不要为某个模式创建孤立能力。
- inline/plain CLI 经 `RuntimeExecutionHost` 只注册当前 workspace，顶层串行。可信 Java 宿主可显式注册多个独立目录，默认顶层并发 2、合法 1～4；同 Session FIFO、同 workspace 仍串行。Dispatcher 预留容量/slot/共享资源，Worker 取得 mutationGate 与 Session lease 后才领取 SQLite Execution。Lanterna 与 Runtime HTTP API 未接入 Host；接口和恢复边界见 [多工作区执行调度方案](docs/dev/42-multi-workspace-execution-scheduling.md)。
- 精确代码定位优先 `glob_files` / `grep_code` / `read_file`；`search_code` 只组合 SQLite FTS5 + BM25、进程内 Qwen3 FP32 1024维 + cosine 两类召回，默认Top10/16000正文字符。语义query保留行为需求，lexical_query是软线索，混合问句也可提取代码名称；不得把grep塞回RAG。候选文件hash诊断不证明全库时效，关键判断/修改前read_file核实当前源码；空索引或失效回到实时定位，不自动全库回填。Graph不参与RAG融合，符号/关系索引继续服务结构地图与`/graph`。Qwen权重需显式预装，故障保留FTS；长期Memory仍BGE，远程Embedding必须有当前项目显式授权。
- system prompt 由 `PromptAssembler` 分层组装，内置 prompt 在 `src/main/resources/prompts/`，支持 `~/.codeagent/prompts/` 和 `.codeagent/prompts/` 覆盖。

## Things That Will Bite You

- 普通运行中直接输入任务会排队串行执行，不补入当前任务。等待审批/计划评审时先处理当前交互，再提交新任务；`/react`、`/plan` 也不能在 pending 时追加。`/task` 只查询、取消和查看日志，add 子命令已移除。

- 交互式 CLI 默认进程内自动维护代码索引（启动对账、监听去抖、周期校准，词法先更新/向量补齐）；`autoIndex.enabled=false` 关闭后台维护，仅查询已有索引。检索自身不全库回填。Runtime API/headless 不隐式创建维护线程，由宿主显式调用底层刷新接口；后台服务不占 Session lease，不自动授权远程 Embedding。
- 默认 headless workspace 工厂不启动 MCP Manager 或后台索引，并拒绝后来出现的未声明 MCP/Browser 可变绑定。共享 Git common-dir 使用整轮粗租约；其他非局部副作用需要显式资源租约，外部命令没有容器隔离。Host 是可信进程内控制面，不从普通输入自动注册目录；交互回答核对 workspaceKey、executionId、interactionId。owner 文件锁先于局部恢复，owner/attempt 阻止迟到写入；子执行器和快照未 drain 时不得释放工作区所有权。

- 开发流程、Git 授权与文档维护边界以 [AGENTS.md](AGENTS.md) 为准；这里只保留操作速查，不复制完整协作规则。
- 改行为更新相关使用说明和模块文档；仅全局规则或边界变化时更新 AGENTS，路线图只在演进状态变化时更新。实现细节与修复记录不得追加到 AGENTS。
- 改命令入口要联动 `Main.java`、`CliCommandParser.java`、相关 parser/completer、测试和 `README.md`。
- 改工具集要联动 `ToolRegistry.java`、Agent/Plan/SubAgent 提示词和文档。
- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取临时事实。
- `ctx` 表示下一轮仍会带入请求的上下文估算；`in/out/cache` 表示最近任务 LLM 调用统计，不要混用。

## Don't

- 不提交 `.env`、真实 API Key、`target/` 产物。
- 不把 `ROADMAP.md` 的未来计划写成已交付能力。
- 不在交互主路径新增裸 `System.out.println`；优先走 `Renderer.stream()`。
