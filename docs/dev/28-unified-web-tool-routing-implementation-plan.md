# Unified Web Tool Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Repository policy forbids agent commits unless the user separately authorizes them, so this plan leaves all changes uncommitted.

**Goal:** Expose only `web_search` and `web_fetch` to the model while routing each stable tool through model-aware auto selection or an explicitly configured Provider/direct/MCP backend.

**Architecture:** `CodeAgentConfig` owns immutable, validated Web route values. A focused `WebToolBackendRouter` decides route and model visibility; `ToolRegistry` keeps execution, argument adaptation, policy, HITL, audit, and URL provenance. MCP tools remain registered internally but reserved Web tools and configured MCP backends are filtered from LLM tool definitions.

**Tech Stack:** Java 17, Jackson, JUnit 5, Maven, existing MCP/SearchProvider/ToolRegistry infrastructure.

## Global Constraints

- Model-visible Web tools are exactly `web_search` and `web_fetch`.
- Missing `webTools` config defaults to model-aware auto routing: Step 3.7 Flash uses StepSearch MCP; other models use SearchProvider/direct.
- Explicit routes override model-aware selection and do not change with `/model`.
- Only `BACKEND_UNAVAILABLE` may use an explicitly configured `onUnavailable=default` fallback.
- Policy denial, HITL rejection, cancellation, invalid configuration, and execution errors never fall back.
- MCP prose never creates URL authority; only structured URL metadata may populate `discoveredUrls`.
- All MCP execution stays on the existing policy/HITL/registry/audit path.
- Do not commit, push, create a PR, or create a worktree.

---

### Task 1: Web Route Configuration

**Files:**
- Modify: `src/main/java/com/codeagent/config/CodeAgentConfig.java`
- Create: `src/test/java/com/codeagent/config/CodeAgentWebToolsConfigTest.java`

**Interfaces:**
- Produces: `CodeAgentConfig.WebToolsConfig`, `WebToolRouteConfig`, `getWebTools()`.
- Defaults: search=`auto`, fetch=`auto`; explicit MCP `onUnavailable`=`fail`.

- [ ] **Step 1: Write failing configuration tests**

```java
@Test
void defaultsToModelAwareAutomaticRouting(@TempDir Path dir) {
    CodeAgentConfig config = CodeAgentConfig.load(dir.resolve("missing.json"), Map.of());
    assertEquals("auto", config.getWebTools().getSearch().getBackend());
    assertEquals("auto", config.getWebTools().getFetch().getBackend());
}

@Test
void loadsExplicitMcpRoutes(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("config.json");
    Files.writeString(file, """
            {"webTools":{"search":{"backend":"mcp","tool":"mcp__step_search__web_search","onUnavailable":"default"},
                         "fetch":{"backend":"mcp","tool":"mcp__step_search__web_fetch"}}}
            """);
    CodeAgentConfig config = CodeAgentConfig.load(file, Map.of());
    assertEquals("mcp__step_search__web_search", config.getWebTools().getSearch().getTool());
    assertEquals("default", config.getWebTools().getSearch().getOnUnavailable());
    assertEquals("fail", config.getWebTools().getFetch().getOnUnavailable());
}
```

- [ ] **Step 2: Run RED**

Run: `mvn test -DskipTests=false "-Dtest=CodeAgentWebToolsConfigTest"`

Expected: compilation failure because `getWebTools()` and nested config types do not exist.

- [ ] **Step 3: Implement the minimum config model**

Add nested Jackson-compatible classes with normalized getters:

```java
private WebToolsConfig webTools = new WebToolsConfig();

public WebToolsConfig getWebTools() {
    if (webTools == null) webTools = new WebToolsConfig();
    return webTools;
}

public static final class WebToolsConfig {
    private WebToolRouteConfig search = WebToolRouteConfig.autoDefault();
    private WebToolRouteConfig fetch = WebToolRouteConfig.autoDefault();
    // no-arg constructor plus null-safe getters/setters
}

public static final class WebToolRouteConfig {
    private String backend;
    private String provider;
    private String tool;
    private String onUnavailable;
    // no-arg constructor, factories, normalized getters/setters
}
```

Normalization must restrict search backends to `auto|provider|mcp`, fetch backends to `auto|direct|mcp`, MCP names to `mcp__*`, and fallback to `fail|default`. Invalid values remain detectable by `validationError(logicalTool)` rather than silently changing backend.

- [ ] **Step 4: Run GREEN**

Run: `mvn test -DskipTests=false "-Dtest=CodeAgentWebToolsConfigTest,CodeAgentEmbeddingConfigTest"`

Expected: all selected tests pass.

---

### Task 2: Typed Tool Failures

**Files:**
- Modify: `src/main/java/com/codeagent/tool/ToolOutput.java`
- Modify: `src/main/java/com/codeagent/tool/ToolRegistry.java`
- Modify: `src/main/java/com/codeagent/hitl/HitlToolRegistry.java`
- Modify: `src/main/java/com/codeagent/mcp/McpServerManager.java`
- Modify: `src/test/java/com/codeagent/tool/ToolRegistryTest.java`
- Modify: `src/test/java/com/codeagent/hitl/HitlToolRegistryTest.java`

**Interfaces:**
- Produces: `ToolOutput.FailureKind`, `failure(kind, text)`, `failureKind()`.
- Existing four-argument constructor and factories remain source compatible.

- [ ] **Step 1: Write failing failure-kind tests**

```java
@Test
void cancellationAndPolicyDenialAreTyped() {
    // execute while CancellationContext is cancelled and assert CANCELLED
    // execute a PathGuard-rejected write and assert POLICY_DENIED
}

@Test
void hitlRejectionIsTyped() {
    // reject execute_command through HitlToolRegistry
    // assert HITL_REJECTED and successful()==false
}
```

- [ ] **Step 2: Run RED**

Run: `mvn test -DskipTests=false "-Dtest=ToolRegistryTest,HitlToolRegistryTest"`

Expected: compilation failure because `FailureKind` and `failureKind()` do not exist.

- [ ] **Step 3: Add failure metadata and classify boundary failures**

Change the record to:

```java
public record ToolOutput(String text,
                         List<LlmClient.ContentPart> imageParts,
                         boolean successful,
                         List<String> discoveredUrls,
                         FailureKind failureKind) {
    public enum FailureKind {
        NONE, INVALID_CONFIGURATION, BACKEND_UNAVAILABLE, TOOL_NOT_FOUND,
        POLICY_DENIED, HITL_REJECTED, CANCELLED, EXECUTION_ERROR
    }
}
```

Keep a four-argument constructor delegating to `NONE` for success and `EXECUTION_ERROR` for failure. Update cancellation, `PolicyException`, HITL reject/skip, missing-tool, and MCP transport exception boundaries to construct the matching kind. Transport failures become `BACKEND_UNAVAILABLE`; JSON-RPC business errors remain `EXECUTION_ERROR`. Do not classify an MCP business response as unavailable merely from its text.

- [ ] **Step 4: Run GREEN**

Run: `mvn test -DskipTests=false "-Dtest=ToolRegistryTest,HitlToolRegistryTest,McpToolRegistrationTest"`

Expected: all selected tests pass.

---

### Task 3: Router and Model Visibility

**Files:**
- Create: `src/main/java/com/codeagent/web/WebToolBackendRouter.java`
- Create: `src/test/java/com/codeagent/web/WebToolBackendRouterTest.java`
- Modify: `src/main/java/com/codeagent/tool/ToolRegistry.java`
- Modify: `src/test/java/com/codeagent/tool/ToolRegistryTest.java`

**Interfaces:**
- Produces: `new WebToolBackendRouter(WebToolsConfig)`, `searchRoute()`, `fetchRoute()`, `isModelVisible(Tool, McpToolDescriptor)`, `mayFallback(ToolOutput)`.
- `ToolRegistry.setWebToolsConfig(WebToolsConfig)` replaces routes atomically.

- [ ] **Step 1: Write failing visibility and routing tests**

```java
@Test
void hidesReservedMcpWebToolsButKeepsThemInternallyExecutable() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.registerMcpTool(stepSearchDescriptor("web_search", SCHEMA), args -> "step:" + args);
    assertFalse(registry.getToolDefinitions().stream()
            .anyMatch(tool -> tool.name().equals("mcp__step_search__web_search")));
    assertTrue(registry.hasTool("mcp__step_search__web_search"));
    assertTrue(registry.executeTool("mcp__step_search__web_search", "{\"query\":\"x\"}").contains("step:"));
}

@Test
void modelChangesDoNotChangeExplicitRoute() {
    // configure provider route, register Step MCP, switch between Step/GLM
    // assert both calls use the same configured provider backend
}
```

Add router unit cases for provider/direct/MCP validation, configured nonstandard MCP backend hiding, and fallback only for `BACKEND_UNAVAILABLE`.

- [ ] **Step 2: Run RED**

Run: `mvn test -DskipTests=false "-Dtest=WebToolBackendRouterTest,ToolRegistryTest"`

Expected: reserved MCP Web tools remain visible and the router type is missing.

- [ ] **Step 3: Implement router, visibility, and explicit dispatch**

Required behavior:

```java
public List<LlmClient.Tool> getToolDefinitions() {
    return tools.values().stream()
            .filter(tool -> webToolBackendRouter.isModelVisible(tool.name(), mcpTools.get(tool.name())))
            .sorted(Comparator.comparing(Tool::name))
            .map(...)
            .toList();
}
```

Move `shouldPreferStepSearch()` into route-based auto resolution. For MCP search/fetch, preserve the current schema-aware optional argument mapping and call `executeToolOutput(configuredTool, json)`. Missing tools and transport failures become `BACKEND_UNAVAILABLE`; auto routes fall back to Provider/direct, while explicit MCP routes do so only with `onUnavailable=default`. Preserve MCP prose as text and discard generic MCP `discoveredUrls` unless a future dedicated trusted adapter is introduced.

- [ ] **Step 4: Run GREEN**

Run: `mvn test -DskipTests=false "-Dtest=WebToolBackendRouterTest,ToolRegistryTest,McpToolRegistrationTest,TurnToolPolicyTest"`

Expected: all selected tests pass.

---

### Task 4: Runtime Wiring and Documentation

**Files:**
- Modify: `src/main/java/com/codeagent/cli/Main.java`
- Modify: `src/test/java/com/codeagent/cli/MainConfigBootstrapTest.java` or add a focused Main wiring test
- Modify: `README.md`
- Modify: `.env.example`
- Modify: `AGENTS.md`
- Modify: `docs/agents-reference.md`
- Update: `docs/dev/27-unified-web-tool-routing.md` only if implementation differs from the approved design

**Interfaces:**
- Interactive and headless registries both receive `config.getWebTools()`.
- MCP lifecycle remains owned by the interactive `McpServerManager`.

- [ ] **Step 1: Write failing wiring test**

Extract or use a package-visible helper that applies runtime configuration:

```java
@Test
void appliesWebToolRoutesToRegistry() {
    CodeAgentConfig config = configuredMcpSearch();
    ToolRegistry registry = new ToolRegistry();
    Main.configureToolRegistry(registry, config);
    assertEquals("mcp__step_search__web_search", registry.getWebToolsConfig().getSearch().getTool());
}
```

- [ ] **Step 2: Run RED**

Run: `mvn test -DskipTests=false "-Dtest=MainConfigBootstrapTest"`

Expected: compilation failure because the shared configuration helper/accessor is missing.

- [ ] **Step 3: Wire config and synchronize documentation**

Apply Web configuration immediately after each shared `ToolRegistry`/`HitlToolRegistry` construction. Do not instantiate MCP Manager in `runHeadlessTask()`; document that MCP routes are unavailable there unless `onUnavailable=default`.

Documentation must state:

```text
- The model sees only web_search and web_fetch.
- Search provider adapters still call external services.
- stdio MCP servers start with the interactive CLI and may finish after the startup wait.
- STEP_API_KEY may auto-configure step_search; the default auto route selects it for Step 3.7 Flash.
```

- [ ] **Step 4: Run targeted integration tests**

Run: `mvn test -DskipTests=false "-Dtest=CodeAgentWebToolsConfigTest,ToolRegistryTest,WebToolBackendRouterTest,McpToolRegistrationTest,TurnToolPolicyTest,HitlToolRegistryTest,MainConfigBootstrapTest"`

Expected: all selected tests pass.

---

### Task 5: Regression and Delivery Verification

**Files:**
- Inspect all changed files; no new production behavior is added in this task.

**Interfaces:**
- Consumes all prior tasks.
- Produces final verification evidence and an uncommitted working tree.

- [ ] **Step 1: Run MCP transport regressions**

Run: `mvn test -DskipTests=false "-Dtest=McpServerManagerTest,StdioTransportTest,StreamableHttpTransportTest"`

Expected: all selected tests pass.

- [ ] **Step 2: Run quick regression**

Run: `mvn test -Pquick`

Expected: BUILD SUCCESS with zero test failures.

- [ ] **Step 3: Run full regression**

Run: `mvn test -DskipTests=false`

Expected: BUILD SUCCESS with zero test failures.

- [ ] **Step 4: Build the deliverable**

Run: `mvn clean package -DskipTests`

Expected: BUILD SUCCESS.

- [ ] **Step 5: Check the diff**

Run: `git diff --check`

Expected: no output and exit code 0. Then inspect `git status --short --branch` and `git diff --stat`; verify no `.env`, API keys, `target/`, raw session content, or unrelated files are included.
