# Windows 跨平台兼容性修复实施计划

> **执行要求：** 使用 `executing-plans` 在当前分支逐任务执行；所有生产代码修改先有可观察的失败测试。步骤使用 checkbox 跟踪。未经用户允许不提交、不推送。

**目标：** 修复 Windows 下图片 URI、路径输出、RAG 项目键、终端换行与命令 Shell 导致的 11 个回归失败，同时保持 Linux/macOS 行为和安全边界。

**架构：** 内部继续使用平台原生 `Path`，在对外文本边界统一为 `/`；RAG 在 `VectorStore` 入口统一项目键；命令执行在策略校验后根据操作系统选择 PowerShell 或 Bash。测试断言使用语义或平台值，不写死 Unix 环境。

**技术栈：** Java 17、JUnit 5、Maven、NIO Path、ProcessBuilder、SQLite。

## 全局约束

- 不安装或依赖 Git Bash/WSL。
- 不修改 ToolPolicy/HITL/AuditLog/PathGuard/CommandGuard 的授权顺序。
- 不修改 RAG schema；旧索引通过 `/index` 重建。
- 不使用 worktree，不执行 commit/push/PR。

---

### Task 1：Windows 本地图片 URI

**文件：**
- Modify: `src/test/java/com/codeagent/image/ImageReferenceParserTest.java`
- Modify: `src/main/java/com/codeagent/image/ImageReferenceParser.java`

**接口：** `fileUriToLocalPath(String)` 继续返回本地路径字符串；新增行为覆盖 Windows drive URI 和 UNC。

- [x] 先增加标准 `file:///D:/...` 与 `file://server/share/...` 的平台条件测试，并复跑现有三个失败用例确认 RED。
- [x] 调整 scheme/authority/drive 拆分：Windows drive 不添加 POSIX `/`，UNC 保留 `\\server\share` 语义，最后执行 UTF-8 percent decode。
- [x] 运行 `mvn test -DskipTests=false -Dtest=ImageReferenceParserTest`，全部通过。

### Task 2：稳定的工具路径输出

**文件：**
- Modify: `src/main/java/com/codeagent/tool/ToolRegistry.java`
- Modify: `src/main/java/com/codeagent/tool/JavaCodeSearchEngine.java`
- Modify: `src/main/java/com/codeagent/tool/RipgrepCodeSearchEngine.java`
- Test: `src/test/java/com/codeagent/tool/ToolRegistryTest.java`
- Test: `src/test/java/com/codeagent/tool/CodeSearchGoldenSetTest.java`

**接口：** 新增包内 `ToolPathFormatter.toPortableString(Path/String)`，只把对外相对路径的 `\\` 转为 `/`。

- [x] 保留当前 `/` 断言并运行失败用例，确认 Windows 原生反斜杠是 RED 原因。
- [x] 新增最小 formatter，并在 glob、Java grep、ripgrep grep 与 suggested reads 的 `GrepMatch.file` 边界调用。
- [x] 运行 `ToolRegistryTest` 的 glob/grep 用例和 `CodeSearchGoldenSetTest`，路径断言全部通过。

### Task 3：RAG 项目键一致性

**文件：**
- Modify: `src/test/java/com/codeagent/rag/CodeRetrieverTest.java`
- Modify: `src/test/java/com/codeagent/rag/VectorStoreTest.java`
- Modify: `src/main/java/com/codeagent/rag/VectorStore.java`
- Modify: `src/main/java/com/codeagent/rag/CodeRetriever.java`

**接口：** `VectorStore.normalizeProjectKey(String)` 负责绝对化、normalize，并在路径存在时使用 real path；所有构造路径都委托这一入口。

- [x] 增加 raw path 写入、等价 absolute path 读取的测试，确认当前返回空结果。
- [x] 在 `VectorStore` 构造器统一项目键，移除 `CodeRetriever` 的重复规范化。
- [x] 运行 `CodeRetrieverTest,VectorStoreTest,CodeIndexTest`，全部通过。

### Task 4：平台换行断言

**文件：**
- Modify: `src/test/java/com/codeagent/render/inline/InlineRendererTest.java`

**接口：** 生产行为不变；测试使用 `System.lineSeparator()` 表达 `PrintStream.println` 契约。

- [x] 将断言改为 `"异步通知" + System.lineSeparator()`；这是测试可移植性修正，不修改生产代码。
- [x] 运行 `InlineRendererTest`，全部通过。

### Task 5：跨平台命令 Shell

**文件：**
- Create: `src/main/java/com/codeagent/tool/CommandShell.java`
- Create: `src/test/java/com/codeagent/tool/CommandShellTest.java`
- Modify: `src/main/java/com/codeagent/tool/ToolRegistry.java`
- Modify: `src/test/java/com/codeagent/tool/ToolRegistryTest.java`

**接口：** `CommandShell.forCurrentPlatform().command(String)` 返回不可变 argv；Windows 为 `powershell.exe -NoProfile -NonInteractive -Command`，其他系统为 `bash -c`。

- [x] 先写 Windows/Unix argv 选择测试，确认类型缺失导致 RED。
- [x] 添加最小 `CommandShell` 并让 `ToolRegistry.executeCommand` 使用其 argv；CommandGuard 仍先执行。
- [x] 将超时测试命令按平台选择为 PowerShell `Start-Sleep -Seconds 2` 或 Bash `sleep 2`。
- [x] 运行 `CommandShellTest,ToolRegistryTest,CommandGuardTest`，全部通过。

### Task 6：Memory 路径测试与总回归

**文件：**
- Modify: `src/test/java/com/codeagent/memory/MemoryManagerTest.java`
- Modify: `docs/dev/18-windows-cross-platform-compatibility.md`

**接口：** Memory 生产逻辑不变；测试通过 `Path` 比较规范化项目键。

- [x] 将 `endsWith("/repo/current")` 改为平台无关的 `Path.endsWith(Path.of("repo", "current"))`。
- [x] 运行六组针对性测试并确认通过（79 个测试，0 失败、0 错误）。
- [ ] 运行 `mvn test -Pquick`、`mvn test -DskipTests=false` 和 `mvn clean package`（quick/full 与普通 package 已通过；clean 受 IDE/语言服务文件锁阻断）。
- [x] 更新设计文档状态与验收清单，运行 `git diff --check` 并审查最终 diff。

## 计划自检

- 六类已知失败均有唯一任务归属，不混入 Mode Router 行为修改。
- 生产修改均有失败测试先行；仅测试可移植性修改不伪造生产行为。
- 路径显示、路径身份和路径授权被明确分离。
- Shell 选择不改变 CommandGuard、环境清理、超时和审计链。
