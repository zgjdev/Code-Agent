# Java 诊断警告清理

## 1. 背景、目标与非目标

### 1.1 背景

VS Code 的 Red Hat Java 扩展使用 Eclipse 编译器（ECJ）分析当前工程，现可稳定复现 167 条 warning 和 6 条 info。它们主要由资源未关闭、弃用兼容 API、未使用声明和缺少 `serialVersionUID` 构成；Maven/Javac 默认输出较少，因此此前只看 Maven 构建会漏掉这些编辑器诊断。

### 1.2 目标

- 以与 VS Code 相同的 ECJ 编译器建立可重复基线，并把 warning/info 清零。
- 修复生产代码中的真实资源生命周期问题。
- 清理无效 import、局部变量、私有成员和不必要的抑制声明。
- 对刻意保留的兼容 API 使用最小范围、带理由的弃用抑制，不删除兼容入口。
- 保持 Java 17、CLI/TUI、RAG、Memory、MCP 和 Renderer 的现有行为不变。

### 1.3 非目标

- 不重构模块架构，不新增功能或命令。
- 不删除 `EmbeddingClient`、旧 RAG 构造器等已记录的兼容 API。
- 不通过全局关闭 VS Code/ECJ 告警来隐藏问题。
- 不修改用户级 IDE 设置或依赖版本。

### 1.4 影响面与验收标准

影响面限于已有 Java 源码、测试资源生命周期和本方案文档。验收标准：ECJ 诊断为 0 warning/0 info，Javac `-Xlint:all` 为 0 warning，针对性测试和 `mvn test -Pquick` 通过，`git diff --check` 通过。

## 2. 现状分析（源码证据、已知约束）

### 2.1 架构位置

诊断跨越入口、Agent、RAG/Memory、MCP、Renderer 和测试代码，但不改变这些模块的依赖关系。`docs/dev/25-simplify-code-rag-retrieval.md` 明确要求保留旧 RAG 构造器作为兼容 overload；因此弃用诊断不能靠删除公共兼容面解决。

### 2.2 数据/状态模型

当前 ECJ 基线共 173 条诊断：167 条 warning、6 条 info。其中包括 80 条资源生命周期、26 条弃用、25 条未使用 import、17 条 `serialVersionUID`、10 条未使用字段/局部变量、8 条未使用方法/构造器和 1 条不必要抑制。资源对象主要实现 `AutoCloseable`，应由创建方或测试夹具在确定的生命周期边界关闭。

### 2.3 核心时序与失败路径

```mermaid
flowchart LR
    S[源码与测试] --> E[ECJ / VS Code Java]
    E --> C{诊断类别}
    C -->|未使用/序列化| M[机械清理]
    C -->|资源生命周期| R[明确所有权并关闭]
    C -->|弃用兼容层| D[迁移内部调用或窄范围抑制]
    M --> V[ECJ + Javac + Maven 验证]
    R --> V
    D --> V
    V -->|仍有诊断或回归| C
    V -->|全部通过| O[交付]
```

主要失败路径是误删反射/兼容入口、提前关闭由异步任务持有的资源、测试夹具关闭顺序错误，以及用宽泛 `@SuppressWarnings` 掩盖真实泄漏。实施时先用引用搜索确认可删除项，再按“最内层资源先关闭”的逆序释放；兼容测试只抑制 `deprecation`，资源告警不以弃用抑制替代。

## 3. 方案设计

### 3.1 接口与数据结构

不新增业务接口或持久化数据结构。异常类补充固定 `serialVersionUID = 1L`；测试优先使用 try-with-resources 或 `@AfterEach` 维护的资源集合。Jackson 的旧式 mapper 配置迁移到 builder API，但保持序列化配置值不变。

### 3.2 策略、安全、并发与恢复

- 删除成员前用全仓引用搜索验证，仅直接删除无引用的 private/package-private 实现细节。
- 生产资源在现有请求/会话边界关闭；不改变并发池、异步回调和共享 provider 的所有权。
- 生产资源在每个生命周期边界真实关闭；仅对无外部句柄、且需要在 open-state 下断言的测试夹具使用测试类级 `resource` 抑制，并写明原因。
- 弃用兼容测试使用方法级或类级最小抑制，并保留兼容行为断言。
- 每批变更后运行对应测试和 ECJ 计数；出现回归时可按批次从未提交 diff 中回滚。

### 3.3 兼容性、迁移与回滚

公共兼容 API 不删除，CLI 命令、配置、数据库 schema 和外部协议不变。改动保持为未提交状态；若某类修复改变生命周期语义，可单独撤销该文件而不影响其他批次。

## 4. 实现任务与测试矩阵

1. [x] 固化 ECJ/Javac 失败基线，确认与 VS Code Problems 一致。
2. [x] 清理未使用 import、局部变量、私有成员、不必要抑制，并补齐异常序列 UID。
3. [x] 修复生产代码资源关闭，运行 WeChat/Renderer/MCP 相关测试。
4. [x] 对无外部句柄的 open-state 测试夹具增加带理由的测试类级 `resource` 抑制。
5. [x] 迁移非兼容路径的弃用调用，对兼容测试使用窄范围抑制。
6. [x] 拆分 LLM retry 辅助异常、收窄 embedding `close()` 异常契约，消除 Javac 额外诊断。
7. [x] 运行 ECJ、Javac `-Xlint:all`、针对性测试、quick 回归和 diff 检查。

| 领域 | 验证 | 预期 |
|---|---|---|
| 静态诊断 | ECJ 全量 main+test | 0 warning，0 info |
| 编译诊断 | Javac 17 `-Xlint:all` | 0 warning |
| MCP/资源 | MCP JSON-RPC、Client、Transport 测试 | 通过且无资源告警 |
| Memory/RAG | Memory、CodeIndex/Retrieval、Embedding 测试 | 行为不变 |
| Renderer | Plain Renderer/Loop 测试 | 输出和关闭语义不变 |
| 全局回归 | `mvn test -Pquick` | 通过 |
| 交付检查 | `git diff --check` | 通过 |

## 5. 验收清单

- [x] ECJ 0 warning / 0 info。
- [x] Javac 17 `-Xlint:all` 0 warning。
- [x] 生产资源均有明确关闭边界。
- [x] 兼容 API 保留，弃用抑制范围最小且有依据。
- [x] 针对性测试通过：172 个测试，0 失败、0 错误。
- [x] `mvn test -Pquick` 通过：1140 个测试，0 失败、0 错误、4 个跳过。
- [x] `git diff --check` 通过，无构建产物进入 diff。

### 5.1 实施记录

- ECJ 基线：主代码与测试合计 167 warning、6 info；完成后两者均无输出、退出码 0。
- Javac `-Xlint:all` 基线：主代码 11 warning、测试 5 warning；完成后两者均无输出、退出码 0。
- `WechatMessageLoop` 的临时 `WechatRenderer` 改为 try-with-resources；Jackson mapper 改用 `JsonMapper.builder()`。
- `EmbeddingProvider.close()` 收窄为不抛受检异常，`CodeRetriever.close()` 收窄为 `SQLException`；原有兼容 API 仍保留。
- JLine `CmdDesc` 仅服务于未接线的 tail-tip helper，已连同对应死测试删除；现有斜杠命令提示与 palette 行为不变。
- 三个 LLM retry 包级异常拆为独立源文件，避免 Javac auxiliary-class 诊断，不改变可见性或构造契约。
- `mvn clean package -DskipTests` 首次执行时，VS Code Red Hat Java Language Server 在 Maven 清理期间重新生成 `target/test-classes`，导致 clean 删除阶段失败；关闭相关 VS Code 窗口后重新执行成功，335 个主源码与 203 个测试源码编译通过并生成 shaded JAR。
- Maven Shade Plugin 仍报告依赖 JAR 中 `module-info.class`、许可证及服务描述文件等元数据重叠；这些是既有打包诊断，与本次 Java 源码 warning 清理无关。该命令使用 `-DskipTests`，测试通过结论来自前述 `mvn test -Pquick` 回归。

### 5.2 无引用演示文件清理（2026-10-19）

目标：删除用户指定的 `src/main/java/com/example/Demo.java`，检查生产源码、内置资源、根目录受控文件和安装脚本是否还有同类演示/临时遗留。非目标：删除测试夹具、兼容 API、历史文档、用户配置、IDE 数据或业务功能；当前分支继续检查，不自动提交或推送。

源码证据：Demo 仅打印 Hello World 和局部变量值，全仓类名/包名搜索没有实际引用；POM 两个 mainClass 均为 `com.codeagent.cli.Main`。`src/test/resources/rag/SampleService.java` 被 CodeAnalyzerTest/CodeChunkerTest 读取，启动器模板被安装与分发脚本使用，Prompt/Skill 资源通过枚举、拼接路径和提取清单加载，不能用简单文本“无直接引用”判断可删。既有 RAG/Memory 兼容构造器与反射测试入口继续保留。

追加引用审查：JsonRpcMessage、McpInitializeResult、TuiHitlHandler、FetchResult 在生产、测试、配置、脚本、文档中均没有外部引用。JsonRpcClient.request/handleMessage 与 McpClient.initialize 直接处理 JsonNode；TuiBootstrap.launch 实际绑定 RendererHitlHandler；WebFetcher.fetch 返回自己的 RawResponse。McpCapabilities 是只被废弃 McpInitializeResult 使用的空 record，随之删除。以上属于未接线实现残留，不是标记保留的兼容 API。评测组件、LegacySessionMigrator、InteractionPresentationQueue 和 RAG 兼容门面虽然只被测试或宿主使用，均不按“仅测试引用”删除。

最小方案评审：移除没有引用和运行职责的 Demo、5 个遗留类及已空目录，不调整 Maven 入口、模块依赖、配置或存储。先完成搜索与当前源码核对，再删除并做干净构建；回滚仅需从 Git 恢复对应文件。此次没有新增行为，因此不编写仅断言文件不存在的单元测试，以原 quick 回归、MCP/TUI 针对性测试、干净编译和最终 JAR 无遗留类为验收证据。

验收：生产源码没有同类第二个演示入口；测试夹具和动态资源保留；`mvn clean test -Pquick`、`mvn test -DskipTests=false -Dtest=JsonRpcClientTest,McpClientTest,RendererHitlHandlerTest,TuiBootstrapTest,HtmlExtractorTest,WebToolBackendRouterTest`、`mvn package -DskipTests` 和 `git diff --check` 通过，最终 JAR 不含上述6个类。

实施结果：已删除6个 Java 文件、空 com/example 与 tui/hitl 目录，未修改运行入口、命令、依赖、授权链或业务逻辑。干净编译447个生产源码、301个测试源码通过。quick 实际1715 tests、0 failures、0 errors、20 skipped，exit0；相关MCP/审批/TUI/Web测试38 tests、0 failures/errors/skipped，exit0；package跳测构建成功，exit0。验证日志放在临时目录 paicli-unused-cleanup-20261019（quick.log/targeted.log/package.log），不进入Git。`jar tf target/codeagent-1.0-SNAPSHOT.jar` 检查6个已删类型及嵌套类均不存在、CLI Main.class仍存在；git diff --check通过。其他无直接生产引用但具有测试、兼容或宿主用途的类型继续保留；用户配置、IDE数据、测试样例与模板未删除。改动保留未提交，无推送。

2026-10-20：用户追加授权提交当前修改。本次仅创建本地清理提交，不推送；提交前复核分支、同名 upstream、diff 和上述未变更代码的验证记录。
