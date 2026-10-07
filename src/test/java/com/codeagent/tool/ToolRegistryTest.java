package com.codeagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeagent.browser.BrowserConnector;
import com.codeagent.config.CodeAgentConfig;
import com.codeagent.mcp.protocol.McpToolDescriptor;
import com.codeagent.rag.CodeRetrievalService;
import com.codeagent.rag.IndexRefreshRequest;
import com.codeagent.rag.IndexRefreshResult;
import com.codeagent.rag.RepositoryMap;
import com.codeagent.rag.RetrievalDiagnostics;
import com.codeagent.rag.RetrievalHit;
import com.codeagent.rag.RetrievalIndexStatus;
import com.codeagent.rag.RetrievalIntent;
import com.codeagent.rag.RetrievalRequest;
import com.codeagent.rag.RetrievalResponse;
import com.codeagent.rag.RetrievalSource;
import com.codeagent.rag.embedding.EmbeddingResolution;
import com.codeagent.runtime.CancellationContext;
import com.codeagent.runtime.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void cancellationFailureIsTyped() {
        ToolRegistry registry = new ToolRegistry();
        CancellationToken token = CancellationContext.startRun();
        token.cancel();
        try {
            ToolOutput output = registry.executeToolOutput("list_dir", "{\"path\":\".\"}");

            assertFalse(output.successful());
            assertEquals(ToolOutput.FailureKind.CANCELLED, output.failureKind());
        } finally {
            CancellationContext.clear(token);
        }
    }

    @Test
    void pathGuardFailureIsTypedAsPolicyDenial(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Path outside = tempDir.getParent().resolve("outside.txt");

        ToolOutput output = registry.executeToolOutput("write_file", MAPPER.createObjectNode()
                .put("path", outside.toString())
                .put("content", "blocked")
                .toString());

        assertFalse(output.successful());
        assertEquals(ToolOutput.FailureKind.POLICY_DENIED, output.failureKind());
    }

    @Test
    void searchCodeExposesDiagnosticsAndArchitectureMap(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        registry.setCodeRetrievalService(new StubRetrievalService());

        String chunks = registry.executeTool("search_code", "{\"query\":\"router\"}");
        String architecture = registry.executeTool("search_code",
                "{\"query\":\"router\",\"intent\":\"architecture\"}");

        assertTrue(chunks.contains("Router.java:4-8"));
        assertTrue(chunks.contains("sources=[FTS_TERMS]"));
        assertTrue(chunks.contains("partial: true"));
        assertTrue(chunks.contains("degraded: [semantic_unavailable]"));
        assertFalse(chunks.contains("repository_map:"));
        assertTrue(architecture.contains("repository_map:"));
        assertTrue(architecture.contains("Router -> Agent"));
    }

    private static final class StubRetrievalService implements CodeRetrievalService {
        private RetrievalRequest lastRequest;
        @Override
        public RetrievalResponse search(RetrievalRequest request) {
            lastRequest = request;
            Optional<RepositoryMap> map = request.intent() == RetrievalIntent.ARCHITECTURE
                    ? Optional.of(new RepositoryMap("Router -> Agent", 4, false))
                    : Optional.empty();
            return new RetrievalResponse(
                    List.of(new RetrievalHit("Router.java", 4, 8, "class", "Router",
                            "class Router {}", 0.75, Set.of(RetrievalSource.FTS_TERMS))),
                    map,
                    new RetrievalDiagnostics("off", Map.of(), Map.of(),
                            List.of("semantic_unavailable"), 2),
                    true);
        }

        @Override public IndexRefreshResult refresh(IndexRefreshRequest request) {
            return new IndexRefreshResult(0, 0, 0, 0, List.of());
        }
        @Override public RetrievalIndexStatus status() {
            return new RetrievalIndexStatus(true, false, 0, 0);
        }
        @Override public void reconfigureEmbedding(EmbeddingResolution resolution) {}
        @Override public void close() {}
    }

    @Test
    void searchCodeUsesChosenDefaultsAndPreservesExplicitTopK(@TempDir Path root) {
        var registry = new ToolRegistry();
        registry.setProjectPath(root.toString());
        var service = new StubRetrievalService();
        registry.setCodeRetrievalService(service);
        for (String args : List.of("{\"query\":\"恢复任务\"}",
                "{\"query\":\"恢复任务\",\"top_k\":\"invalid\"}")) {
            registry.executeTools(List.of(new ToolRegistry.ToolInvocation("default", "search_code", args)));
            assertEquals(10, service.lastRequest.topK());
            assertEquals(16_000, service.lastRequest.maxChars());
        }
        for (int explicit : List.of(1, 15, 20, 0, 99)) {
            registry.executeTools(List.of(new ToolRegistry.ToolInvocation("explicit", "search_code",
                    MAPPER.createObjectNode().put("query", "恢复任务").put("top_k", explicit).toString())));
            assertEquals(Math.max(1, Math.min(30, explicit)), service.lastRequest.topK());
            assertEquals(16_000, service.lastRequest.maxChars());
        }
        var definition = registry.getToolDefinitions().stream().filter(t -> t.name().equals("search_code")).findFirst().orElseThrow();
        assertTrue(definition.description().contains("top_k=10"));
    }

    @Test void searchCodePassesSoftLexicalHintWithoutChangingSemanticQuery(@TempDir Path root) {
        var registry=new ToolRegistry(); registry.setProjectPath(root.toString());
        var service=new StubRetrievalService(); registry.setCodeRetrievalService(service);
        registry.executeTools(List.of(new ToolRegistry.ToolInvocation("mixed","search_code",
                "{\"query\":\"保存失败的异常和事务处理\",\"lexical_query\":\"store.save()\"}")));
        assertEquals("保存失败的异常和事务处理",service.lastRequest.query());
        assertEquals("store.save()",service.lastRequest.lexicalQuery());
    }

    @Test
    void executionResultCarriesTypedFailureStatus() {
        ToolRegistry registry = new ToolRegistry();

        ToolOutput output = registry.executeToolOutput("missing_tool", "{}");
        ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("missing", "missing_tool", "{}"))).get(0);

        assertFalse(output.successful());
        assertFalse(result.successful());
        assertTrue(result.discoveredUrls().isEmpty());
    }

    @Test
    void exposesToolDefinitionsInStableNameOrder() {
        ToolRegistry registry = new ToolRegistry();

        List<String> names = registry.getToolDefinitions().stream()
                .map(com.codeagent.llm.LlmClient.Tool::name)
                .toList();
        List<String> sorted = names.stream().sorted().toList();

        assertEquals(sorted, names);
    }

    @Test
    void removesCredentialsButKeepsBuildEnvironmentForBenchmarkShells() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", "/usr/bin");
        environment.put("JAVA_HOME", "/jdk");
        environment.put("DEEPSEEK_API_KEY", "secret");
        environment.put("ANTHROPIC_AUTH_TOKEN", "secret");
        environment.put("AWS_SECRET_ACCESS_KEY", "secret");
        environment.put("DATABASE_PASSWORD", "secret");

        ToolRegistry.removeSensitiveCommandEnvironment(environment);

        assertEquals(Map.of("PATH", "/usr/bin", "JAVA_HOME", "/jdk"), environment);
    }

    @Test
    void shouldRunCommandInProjectDirectory(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"pwd\"}");

        assertTrue(result.contains(tempDir.toString()));
    }

    @Test
    void shouldRejectBroadFilesystemScan() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("execute_command", "{\"command\":\"find / -name \\\"pom.xml\\\" -type f | head -20\"}");

        assertTrue(result.contains("策略拒绝"));
    }

    @Test
    void shouldReadRequestedLineRange(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, String.join("\n",
                "class Sample {",
                "  void first() {}",
                "  void second() {}",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_file", "{\"path\":\"Sample.java\",\"offset\":2,\"limit\":2}");

        assertTrue(result.contains("lines 2-3 of 4"));
        assertTrue(result.contains("2 |   void first() {}"));
        assertTrue(result.contains("3 |   void second() {}"));
        assertTrue(!result.contains("class Sample {"));
    }

    @Test
    void shouldGlobFilesInsideProject(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), "class UserService {}\n");
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"**/*Service.java\"}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java"));
        assertTrue(!result.contains("README.md"));
    }

    @Test
    void shouldGlobRootFileByName(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"README.md\"}");

        assertTrue(result.contains("README.md"));
    }

    @Test
    void shouldGrepCodeWithLineNumbersAndContext(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), String.join("\n",
                "class UserService {",
                "  User getUserById(String id) {",
                "    return repository.findById(id);",
                "  }",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code",
                "{\"pattern\":\"getUserById\",\"glob\":\"**/*.java\",\"context_lines\":1}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java:2"));
        assertTrue(result.contains(">    2 |   User getUserById(String id) {"));
        assertTrue(result.contains("     3 |     return repository.findById(id);"));
    }

    @Test
    void shouldSkipCommonDependencyDirectoriesWhenGrepping(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        Files.writeString(tempDir.resolve("src/App.java"), "class App { String marker = \"targetSymbol\"; }\n");
        Files.writeString(tempDir.resolve("node_modules/pkg/Generated.java"), "class Generated { String marker = \"targetSymbol\"; }\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code", "{\"pattern\":\"targetSymbol\",\"max_results\":10}");

        assertTrue(result.contains("src/App.java:1"));
        assertTrue(!result.contains("node_modules"));
    }

    @Test
    void shouldExposePartialWhenGrepReachesHeadLimit(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("codeagent.search.disable.rg");
        System.setProperty("codeagent.search.disable.rg", "true");
        try {
            Files.writeString(tempDir.resolve("Many.java"), String.join("\n",
                    "class Many {",
                    "  String first = \"needle\";",
                    "  String second = \"needle\";",
                    "}"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("grep_code",
                    "{\"pattern\":\"needle\",\"head_limit\":1,\"max_results\":10}");

            assertTrue(result.contains("Many.java:2"));
            assertTrue(!result.contains("Many.java:3"));
            assertTrue(result.contains("partial: true"));
            assertTrue(result.contains("head_limit=1"));
            assertTrue(result.contains("suggested_reads"));
            assertTrue(result.contains("read_file {\"path\":\"Many.java\""));
        } finally {
            restoreSystemProperty("codeagent.search.disable.rg", previous);
        }
    }

    @Test
    void shouldExposePartialWhenGrepResultReachesCharacterBudget(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("codeagent.search.disable.rg");
        System.setProperty("codeagent.search.disable.rg", "true");
        try {
            String longNeedleLine = "needle " + "x".repeat(1200);
            Files.writeString(tempDir.resolve("Budget.java"), String.join("\n",
                    "class Budget {",
                    "  String first = \"" + longNeedleLine + "\";",
                    "  String second = \"" + longNeedleLine + "\";",
                    "}"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("grep_code",
                    "{\"pattern\":\"needle\",\"max_results\":10,\"max_chars\":1000}");

            assertTrue(result.contains("Budget.java:2"));
            assertTrue(result.contains("partial: true"));
            assertTrue(result.contains("max_chars=1000"));
        } finally {
            restoreSystemProperty("codeagent.search.disable.rg", previous);
        }
    }

    @Test
    void shouldTimeoutLongRunningCommandWithoutHanging(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry(1);
        registry.setProjectPath(tempDir.toString());
        String command = CommandShell.forCurrentPlatform().isWindows()
                ? "Start-Sleep -Seconds 2"
                : "sleep 2";

        String result = registry.executeTool(
                "execute_command", "{\"command\":\"" + command + "\"}");

        assertTrue(result.contains("命令执行超时"));
    }

    @Test
    void shouldRouteWebSearchThroughConfiguredMcpBackend() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setWebToolsConfig(mcpWebTools("mcp__anysearch__search", null, "fail"));
        registry.registerMcpTool(anySearchDescriptor(), args -> "step-result:" + args);

        ToolOutput output = registry.executeToolOutput(
                "web_search", "{\"query\":\"Step 3.7 Flash\",\"top_k\":3}");
        String result = output.text();

        assertTrue(result.contains("[MCP]"));
        assertTrue(result.contains("step-result"));
        assertTrue(result.contains("\"query\":\"Step 3.7 Flash\""));
        assertTrue(result.contains("\"max_results\":3"));
        assertTrue(output.successful());
        assertTrue(output.discoveredUrls().isEmpty(),
                "unstructured MCP prose must not grant URL provenance");
    }

    @Test
    void configuredMcpSearchCannotMintUrlAuthorityWithoutTrustedAdapter() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setWebToolsConfig(mcpWebTools("mcp__anysearch__search", null, "fail"));
        registry.registerMcpToolOutput(anySearchDescriptor(), args -> ToolOutput.discovered("result", List.of("https://untrusted.example/path")));

        ToolOutput output = registry.executeToolOutput("web_search", "{\"query\":\"routing\"}");

        assertTrue(output.successful());
        assertTrue(output.discoveredUrls().isEmpty());
    }

    @Test
    void anySearchPublishesOnlyResultFieldUrls() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.registerMcpTool(anySearchDescriptor(), args -> """
                ## Search Results (1 results, 7ms)

                ### 1. Documentation
                - **URL**: https://example.com/article
                - snippet mentions https://evil.example
                """);
        ToolOutput output = registry.executeToolOutput("web_search", "{\"query\":\"target\"}");
        assertTrue(output.successful());
        assertEquals(List.of("https://example.com/article"), output.discoveredUrls());
        assertTrue(output.text().contains("https://evil.example"));
    }

    @Test
    void anySearchResultsAuthorizeFetchButNotSnippetLinks() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.registerMcpTool(anySearchDescriptor(), args -> """
                ## Search Results (1 results, 7ms)

                ### 1. Documentation
                - **URL**: https://203.0.113.10/article
                - snippet https://evil.example
                """);
        registry.registerMcpTool(new McpToolDescriptor("anysearch", "extract", "mcp__anysearch__extract",
                "fetch", MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}")),
                args -> "{\"url\":\"https://203.0.113.10/article\",\"title\":\"Doc\",\"content\":\"page body\"}");
        registry.setWebToolsConfig(mcpWebTools(null, "mcp__anysearch__extract", "fail"));
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("请联网搜索Java文档并阅读搜索结果");
        var search = policy.execute(registry, List.of(new ToolRegistry.ToolInvocation(
                "search", "web_search", "{\"query\":\"Java docs\"}")), policy.expose(registry.getToolDefinitions()));
        assertTrue(search.get(0).successful());
        var fetch = policy.execute(registry, List.of(new ToolRegistry.ToolInvocation(
                "fetch", "web_fetch", "{\"url\":\"https://203.0.113.10/article\"}")), policy.expose(registry.getToolDefinitions()));
        assertTrue(fetch.get(0).successful(), fetch.get(0).result());
        var denied = policy.execute(registry, List.of(new ToolRegistry.ToolInvocation(
                "bad", "web_fetch", "{\"url\":\"https://evil.example\"}")), policy.expose(registry.getToolDefinitions()));
        assertFalse(denied.get(0).successful());
    }

    @Test
    void anySearchFailureIsSanitizedAndDoesNotAuthorizeUrls() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        for (ToolOutput.FailureKind kind : List.of(ToolOutput.FailureKind.HITL_REJECTED,
                ToolOutput.FailureKind.CANCELLED, ToolOutput.FailureKind.BACKEND_UNAVAILABLE,
                ToolOutput.FailureKind.EXECUTION_ERROR)) {
            registry.registerMcpToolOutput(anySearchDescriptor(), args -> ToolOutput.failure(kind,
                    "api_key=secret-value https://evil.example"));
            var result = registry.executeToolOutput("web_search", "{\"query\":\"docs\"}");
            assertFalse(result.successful());
            assertEquals(kind, result.failureKind());
            assertFalse(result.text().contains("secret-value"));
            assertTrue(result.discoveredUrls().isEmpty());
        }
    }

    @Test
    void shouldRouteWebFetchThroughConfiguredMcpBackend() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setWebToolsConfig(mcpWebTools(null, "mcp__step_search__web_fetch", "fail"));
        registry.registerMcpTool(stepSearchDescriptor("web_fetch", """
                {
                  "type": "object",
                  "properties": {
                    "url": {"type": "string"},
                    "max_chars": {"type": "integer"}
                  }
                }
                """), args -> "step-fetch:" + args);

        String result = registry.executeTool("web_fetch",
                "{\"url\":\"https://203.0.113.10/docs/step-search\",\"max_chars\":1200}");

        assertTrue(result.contains("[MCP]"));
        assertTrue(result.contains("step-fetch"));
        assertTrue(result.contains("\"url\":\"https://203.0.113.10/docs/step-search\""));
        assertTrue(result.contains("\"max_chars\":1200"));
    }

    @Test
    void allModelsUseAnySearchWithoutExposingRawTools() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.registerMcpTool(anySearchDescriptor(), args -> "anysearch-result:" + args);
        for (String provider : List.of("step", "glm", "deepseek")) {
            registry.setCurrentModel(provider, "step-3.7-flash");
            String result = registry.executeTool("web_search", "{\"query\":\"docs\",\"top_k\":3}");
            assertTrue(result.contains("anysearch-result"));
            assertTrue(result.contains("\"max_results\":3"));
        }
        assertFalse(registry.getToolDefinitions().stream()
                .anyMatch(tool -> tool.name().equals("mcp__anysearch__search")));
    }

    @Test
    void rejectsLegacyProviderRoute() {
        ToolRegistry registry = new ToolRegistry();
        CodeAgentConfig.WebToolsConfig config = new CodeAgentConfig.WebToolsConfig();
        CodeAgentConfig.WebToolRouteConfig search = new CodeAgentConfig.WebToolRouteConfig();
        search.setBackend("provider");
        config.setSearch(search);
        registry.setWebToolsConfig(config);
        assertEquals(ToolOutput.FailureKind.INVALID_CONFIGURATION,
                registry.executeToolOutput("web_search", "{\"query\":\"routing\"}").failureKind());
    }

    @Test
    void unavailableAnySearchFallsBackToStepOnlyOnceAndAdaptsN() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger stepCalls = new AtomicInteger();
        registry.registerMcpToolOutput(stepSearchDescriptor("web_search", "{\"type\":\"object\",\"properties\":{\"query\":{},\"n\":{}}}"), args -> {
            stepCalls.incrementAndGet();
            assertTrue(args.contains("\"n\":3"));
            var data = MAPPER.createObjectNode();
            data.putArray("results").addObject().put("url", "https://203.0.113.10/page").put("snippet", "https://evil.example");
            return new ToolOutput(data.toString(), List.of(), true, List.of(), ToolOutput.FailureKind.NONE, data);
        });
        ToolOutput out = registry.executeToolOutput("web_search", "{\"query\":\"docs\",\"top_k\":3}");
        assertTrue(out.successful(), out.text());
        assertTrue(out.text().contains("Step"));
        assertEquals(1, stepCalls.get());
        assertEquals(List.of("https://203.0.113.10/page"), out.discoveredUrls());
    }

    @Test
    void stepJsonLookingProseCannotCreateAuthority() throws Exception {
        var registry = new ToolRegistry();
        registry.registerMcpTool(stepSearchDescriptor("web_search", "{\"type\":\"object\"}"),
                args -> "{\"results\":[{\"url\":\"https://evil.example\"}]}");
        var out = registry.executeToolOutput("web_search", "{\"query\":\"docs\"}");
        assertTrue(out.successful());
        assertTrue(out.discoveredUrls().isEmpty());
    }

    @Test
    void cancellationDuringPrimaryCallPreventsFallbackAndAuthority() throws Exception {
        var registry = new ToolRegistry();
        AtomicInteger stepCalls = new AtomicInteger();
        var token = com.codeagent.runtime.CancellationContext.startRun();
        try {
            registry.registerMcpToolOutput(anySearchDescriptor(), args -> {
                token.cancel();
                return ToolOutput.failure(ToolOutput.FailureKind.BACKEND_UNAVAILABLE, "timeout");
            });
            registry.registerMcpTool(stepSearchDescriptor("web_search", "{\"type\":\"object\"}"), args -> {
                stepCalls.incrementAndGet(); return "unexpected";
            });
            var out = registry.executeToolOutput("web_search", "{\"query\":\"docs\"}");
            assertEquals(ToolOutput.FailureKind.CANCELLED, out.failureKind());
            assertTrue(out.discoveredUrls().isEmpty());
            assertEquals(0, stepCalls.get());
        } finally {
            com.codeagent.runtime.CancellationContext.clear(token);
        }
    }

    @Test
    void businessRejectionAndCancellationNeverFallback() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger stepCalls = new AtomicInteger();
        registry.registerMcpTool(stepSearchDescriptor("web_search", "{\"type\":\"object\"}"), args -> {
            stepCalls.incrementAndGet(); return "unexpected";
        });
        for (var kind : List.of(ToolOutput.FailureKind.EXECUTION_ERROR, ToolOutput.FailureKind.HITL_REJECTED,
                ToolOutput.FailureKind.POLICY_DENIED, ToolOutput.FailureKind.CANCELLED)) {
            registry.registerMcpToolOutput(anySearchDescriptor(), args -> ToolOutput.failure(kind, "secret"));
            assertEquals(kind, registry.executeToolOutput("web_search", "{\"query\":\"docs\"}").failureKind());
        }
        assertEquals(0, stepCalls.get());
    }

    @Test
    void anySearchExtractTruncatesContentAndNeverAuthorizesLinks() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.registerMcpTool(new McpToolDescriptor("anysearch", "extract", "mcp__anysearch__extract", "extract",
                MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"url\":{}}}")), args -> {
            assertFalse(args.contains("max_chars"));
            return "{\"url\":\"https://203.0.113.10/page\",\"title\":\"Doc\",\"content\":\"abcdefghijk https://evil.example\"}";
        });
        var out = registry.executeToolOutput("web_fetch", "{\"url\":\"https://203.0.113.10/page\",\"max_chars\":5}");
        assertTrue(out.successful(), out.text());
        assertTrue(out.text().contains("abcde"));
        assertFalse(out.text().contains("abcdef"));
        assertFalse(out.text().contains("evil.example"));
        assertTrue(out.discoveredUrls().isEmpty());
    }

    @Test
    void fetchFallbackUsesStepAndLocallyTruncates() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.registerMcpTool(stepSearchDescriptor("web_fetch", "{\"type\":\"object\",\"properties\":{\"url\":{}}}"), args -> "abcdefghijk");
        var out = registry.executeToolOutput("web_fetch", "{\"url\":\"https://203.0.113.10/page\",\"max_chars\":5}");
        assertTrue(out.successful(), out.text());
        assertTrue(out.text().contains("Step"));
        assertTrue(out.text().contains("abcde"));
        assertFalse(out.text().contains("abcdef"));
        assertTrue(out.discoveredUrls().isEmpty());
    }

    @Test
    void unavailableAnySearchNeverFallsBack() {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.7-flash");
        ToolOutput output = registry.executeToolOutput("web_search", "{\"query\":\"routing\"}");
        assertFalse(output.successful());
        assertEquals(ToolOutput.FailureKind.BACKEND_UNAVAILABLE, output.failureKind());
        assertTrue(output.discoveredUrls().isEmpty());
    }

    @Test
    void hidesReservedMcpWebToolsButKeepsThemInternallyExecutable() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.registerMcpTool(stepSearchDescriptor("web_search", """
                {"type":"object","properties":{"query":{"type":"string"}}}
                """), args -> "step-result:" + args);

        assertFalse(registry.getToolDefinitions().stream()
                .anyMatch(tool -> tool.name().equals("mcp__step_search__web_search")));
        assertTrue(registry.hasTool("mcp__step_search__web_search"));
        assertTrue(registry.executeTool("mcp__step_search__web_search", "{\"query\":\"x\"}")
                .contains("step-result"));
    }

    @Test
    void shouldExecuteMultipleToolInvocationsInParallelAndKeepResultOrder() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                int now = current.incrementAndGet();
                peak.updateAndGet(prev -> Math.max(prev, now));
                bothStarted.countDown();
                try {
                    assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "两个工具调用应同时进入执行区");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "first", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "second", "{}")
        ));

        assertEquals(2, peak.get(), "两个工具调用应并行执行");
        assertEquals("call_1", results.get(0).id());
        assertEquals("result-first", results.get(0).result());
        assertEquals("call_2", results.get(1).id());
        assertEquals("result-second", results.get(1).result());
    }

    @Test
    void recomputesResourceIdentityAfterEarlierBatchChangesFilesystem(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("original.txt"), "old-value");
        CountDownLatch readFinished = new CountDownLatch(1);
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String arguments) {
                try {
                    if ("execute_command".equals(name)) {
                        Files.createLink(tempDir.resolve("alias.txt"), tempDir.resolve("original.txt"));
                        return "created link";
                    }
                    if ("write_file".equals(name)) {
                        readFinished.await(300, TimeUnit.MILLISECONDS);
                    }
                    String result = super.executeTool(name, arguments);
                    if ("read_file".equals(name)) {
                        readFinished.countDown();
                    }
                    return result;
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
        registry.setProjectPath(tempDir.toString());

        var results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("link", "execute_command", "{\"command\":\"fixture\"}"),
                new ToolRegistry.ToolInvocation("write", "write_file", "{\"path\":\"original.txt\",\"content\":\"new-value\"}"),
                new ToolRegistry.ToolInvocation("read", "read_file", "{\"path\":\"alias.txt\"}")));

        assertEquals(List.of("link", "write", "read"), results.stream().map(ToolRegistry.ToolExecutionResult::id).toList());
        assertTrue(results.get(2).result().contains("new-value"), results.get(2).result());
    }

    @Test
    void shouldExecuteBrowserContainingBatchSequentiallyInDeclaredOrder() {
        CountDownLatch laterCallEntered = new CountDownLatch(1);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<String> executionOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                int now = current.incrementAndGet();
                peak.updateAndGet(previous -> Math.max(previous, now));
                executionOrder.add(name);
                try {
                    if ("mcp__chrome-devtools__new_page".equals(name)) {
                        laterCallEntered.await(300, TimeUnit.MILLISECONDS);
                    } else {
                        laterCallEntered.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation(
                        "browser", "mcp__chrome-devtools__new_page", "{}"),
                new ToolRegistry.ToolInvocation("local", "read_file", "{}")
        ));

        assertEquals(1, peak.get(), "含浏览器工具的整个批次应串行执行");
        assertEquals(List.of("mcp__chrome-devtools__new_page", "read_file"), executionOrder);
        assertEquals(List.of("browser", "local"), results.stream()
                .map(ToolRegistry.ToolExecutionResult::id)
                .toList());
    }

    private static McpToolDescriptor stepSearchDescriptor(String name, String schema) throws Exception {
        JsonNode inputSchema = MAPPER.readTree(schema);
        return new McpToolDescriptor(
                "step_search",
                name,
                "mcp__step_search__" + name,
                "StepSearch " + name,
                inputSchema);
    }

    private static McpToolDescriptor anySearchDescriptor() throws Exception {
        return new McpToolDescriptor("anysearch", "search", "mcp__anysearch__search", "search",
                MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"max_results\":{\"type\":\"integer\"}}}"));
    }

    private static CodeAgentConfig.WebToolsConfig mcpWebTools(String searchTool, String fetchTool,
                                                               String onUnavailable) {
        CodeAgentConfig.WebToolsConfig config = new CodeAgentConfig.WebToolsConfig();
        if (searchTool != null) {
            CodeAgentConfig.WebToolRouteConfig search = new CodeAgentConfig.WebToolRouteConfig();
            search.setBackend("mcp");
            search.setTool(searchTool);
            search.setOnUnavailable(onUnavailable);
            config.setSearch(search);
        }
        if (fetchTool != null) {
            CodeAgentConfig.WebToolRouteConfig fetch = new CodeAgentConfig.WebToolRouteConfig();
            fetch.setBackend("mcp");
            fetch.setTool(fetchTool);
            fetch.setOnUnavailable(onUnavailable);
            config.setFetch(fetch);
        }
        return config;
    }

    @Test
    void shouldCancelToolInvocationWhenBatchTimeoutIsReached() {
        ToolRegistry registry = new ToolRegistry(1, 1) {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if ("slow".equals(name)) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "slow", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "fast", "{}")
        ));

        assertTrue(results.get(0).timedOut());
        assertTrue(results.get(0).result().contains("工具执行超时"));
        assertEquals("result-fast", results.get(1).result());
    }

    @Test
    void browserConnectToolUsesInjectedConnector() {
        ToolRegistry registry = new ToolRegistry();
        registry.setBrowserConnector(new BrowserConnector() {
            @Override
            public String status() {
                return "status-ok";
            }

            @Override
            public String connectDefault() {
                return "connected";
            }

            @Override
            public String disconnect() {
                return "disconnected";
            }
        });

        assertEquals("connected", registry.executeTool("browser_connect", "{}"));
        assertEquals("status-ok", registry.executeTool("browser_status", "{}"));
        assertEquals("disconnected", registry.executeTool("browser_disconnect", "{}"));
    }

    @Test
    void saveMemoryToolUsesInjectedMemorySaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setMemorySaver(saved::add);

        String result = registry.executeTool("save_memory", "{\"fact\":\"访问 yuque.com 时复用登录态\"}");

        assertEquals(List.of("访问 yuque.com 时复用登录态"), saved);
        assertTrue(result.contains("已保存到长期记忆"));
    }

    @Test
    void saveMemoryToolPassesScopeToScopedSaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setScopedMemorySaver((fact, scope) -> saved.add(scope + ":" + fact));

        String result = registry.executeTool("save_memory", "{\"fact\":\"默认用中文回答\",\"scope\":\"global\"}");

        assertEquals(List.of("global:默认用中文回答"), saved);
        assertTrue(result.contains("长期记忆(global)"));
    }

    @Test
    void saveMemoryToolReturnsResolvedWriteOutcome() {
        ToolRegistry registry = new ToolRegistry();
        registry.setMemoryWriter((fact, scope) ->
                "💾 已更新长期记忆(" + scope + "): " + fact);

        String result = registry.executeTool(
                "save_memory",
                "{\"fact\":\"用户偏好使用 Python\",\"scope\":\"global\"}");

        assertEquals("💾 已更新长期记忆(global): 用户偏好使用 Python", result);
    }

    private static void restoreSystemProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
