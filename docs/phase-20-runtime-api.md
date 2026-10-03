# 第 20 期：异步后台任务 + Runtime API

> 当前状态（2026-10-03）：第 20 期 Runtime API 已落地；交互式 CLI 的后台任务入口现已升级为 [统一后台执行 Runtime](dev/31-unified-background-execution-runtime.md)。第 21 期图片输入不依赖本期 API。

## 已交付

### 后台任务

- inline/plain 的普通输入、`/react <任务>`、`/plan <任务>` 和 `/task add <任务>` 共用 `RuntimeExecutionQueue`，Execution 持久化到 SQLite `runtime_executions`；旧任务表保留并迁移为 legacy row
- 默认数据库：`~/.codeagent/tasks/tasks.db`
- 固定单 Worker，按 Session ordinal 保持同一 Session 的 FIFO；运行前取得 Session 写租约，并按需恢复独立的会话上下文
- Agent 运行时直接输入新任务即可入队；等待 HITL / 计划确认时，普通输入优先回答交互，此时用 `/task add` 明确排队
- 恢复按 Execution 模式和状态处理，不再将所有遗留运行任务盲目重新执行；Plan 从 Task 边界恢复，不保证外部副作用 exactly-once
- `CODEAGENT_TASK_WORKERS` / `-Dcodeagent.task.workers` 仅供旧 `DurableTaskManager` 使用，当前 CLI 统一队列不读取它们
- CLI 命令：
  - `/task` 或 `/task list [N]`
  - `/task add <任务内容>`
  - `/task cancel <task_id>`
  - `/task log <task_id>`

### Runtime API

实现位于 `src/main/java/com/codeagent/runtime/api/`，使用 JDK 内置 `HttpServer`，不引入 Spring / Javalin。

启动：

```bash
CODEAGENT_RUNTIME_API_KEY=your_local_api_key \
java -jar target/codeagent-1.0-SNAPSHOT.jar serve --http --port 8080
```

安全策略：

- 仅监听 `127.0.0.1`
- 必须配置 `CODEAGENT_RUNTIME_API_KEY` 或 `-Dcodeagent.runtime.api.key`
- 请求头支持：
  - `Authorization: Bearer <key>`
  - `X-CodeAgent-API-Key: <key>`

端点：

- `POST /v1/threads`：创建 thread
- `POST /v1/threads/{id}/turns`：提交一轮 Agent 输入，异步执行
- `GET /v1/threads/{id}/events`：以 SSE 格式回放事件

事件类型：

- `thread.created`
- `turn.started`
- `message.delta`
- `turn.completed`
- `turn.failed`

## 当前边界

- Runtime API MVP 是事件回放式 SSE，不做长连接持续阻塞推送
- Runtime API 仍使用独立 headless ReAct 路径，不接入统一 CLI 队列、Mode Router 或终端 HITL；Lanterna 与 WeChat 也未接入统一队列
- CLI 通过 `InteractionBroker` 分流审批与计划交互；取消通过中断与持久化终态收敛，远端 HTTP 能否立即停止取决于底层 client
- 退出 CLI 不会启动脱离进程的后台服务；保留非终态记录供后续恢复
- Runtime API 当前不模拟完整 OpenAI Assistants API schema，只保留兼容方向的 threads / turns / events 主路径

## 验证

```bash
mvn test -DskipTests=false "-Dtest=RuntimeExecutionStoreTest,RuntimeExecutionQueueTest,WorkspaceAwareExecutionSchedulerTest,TopLevelExecutionCoordinatorTest,InteractionInputRouterTest,RuntimeApiServerTest,CliCommandParserTest"
```

建议回归：

```bash
mvn test -Pquick
mvn test
mvn -q clean package -DskipTests
CODEAGENT_RUNTIME_API_KEY=test java -jar target/codeagent-1.0-SNAPSHOT.jar serve --http --port 0
```
