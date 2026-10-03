package com.codeagent.hitl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.codeagent.browser.BrowserGuard;
import com.codeagent.browser.BrowserSession;
import com.codeagent.browser.SensitivePagePolicy;
import com.codeagent.mcp.protocol.McpToolDescriptor;
import com.codeagent.tool.ToolOutput;
import com.codeagent.tool.ToolRegistry;
import com.codeagent.config.CodeAgentConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class HitlToolRegistryTest {

    // ------------------ 旁路行为（原有测试保留） ------------------

    @Test
    void disabledHitlPassesThroughToParent() {
        TerminalHitlHandler handler = new TerminalHitlHandler(false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);

        assertFalse(handler.isEnabled());
        handler.setEnabled(true);
        // list_dir 不是危险工具，HITL 启用也应直接通过
        String result = registry.executeTool("list_dir", "{\"path\": \".\"}");
        assertNotNull(result);
        assertFalse(result.startsWith("[HITL]"));
    }

    @Test
    void hitlHandlerIsReturnedFromGetter() {
        TerminalHitlHandler handler = new TerminalHitlHandler(false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        assertSame(handler, registry.getHitlHandler());
    }

    @Test
    void enableAndDisableHitl() {
        TerminalHitlHandler handler = new TerminalHitlHandler(false);
        assertFalse(handler.isEnabled());
        handler.setEnabled(true);
        assertTrue(handler.isEnabled());
        handler.setEnabled(false);
        assertFalse(handler.isEnabled());
    }

    @Test
    void clearApprovedAllResetsState() {
        TerminalHitlHandler handler = new TerminalHitlHandler(true);
        handler.clearApprovedAll();
        assertTrue(handler.isEnabled());
    }

    // ------------------ 开启 HITL 后的决策分支（新增） ------------------

    @Test
    void rejectedDecisionBlocksExecutionAndReturnsRejectMessage(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("should-not-exist.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.reject("too risky"));
        HitlToolRegistry registry = new HitlToolRegistry(stub);

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"x\"}");

        assertTrue(result.startsWith("[HITL]"), "结果应为 HITL 拒绝消息: " + result);
        assertTrue(result.contains("too risky"));
        assertFalse(Files.exists(target), "拒绝后文件不应被创建");
        assertEquals(1, stub.requestCount(), "应只发起一次审批");
    }

    @Test
    void rejectedDecisionReturnsTypedFailure(@TempDir Path tempDir) {
        StubHandler stub = new StubHandler(req -> ApprovalResult.reject("too risky"));
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        ToolOutput output = registry.executeToolOutput("write_file",
                "{\"path\":\"blocked.txt\",\"content\":\"x\"}");

        assertFalse(output.successful());
        assertEquals(ToolOutput.FailureKind.HITL_REJECTED, output.failureKind());
    }

    @Test
    void skippedDecisionBlocksExecution(@TempDir Path tempDir) {
        Path target = tempDir.resolve("skipped.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.skip());
        HitlToolRegistry registry = new HitlToolRegistry(stub);

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"x\"}");

        assertTrue(result.startsWith("[HITL]"), "结果应为 HITL 跳过消息: " + result);
        assertTrue(result.contains("跳过"));
        assertFalse(Files.exists(target));
    }

    @Test
    void approvedDecisionExecutesToolWithOriginalArgs(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("approved.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"approved\"}");

        assertFalse(result.startsWith("[HITL]"));
        assertTrue(Files.exists(target));
        assertEquals("approved", Files.readString(target));
    }

    @Test
    void modifiedDecisionExecutesToolWithModifiedArgs(@TempDir Path tempDir) throws Exception {
        Path original = tempDir.resolve("original.txt");
        Path modified = tempDir.resolve("modified.txt");

        String modifiedArgs = "{\"path\":\"" + modified.toString().replace("\\", "\\\\") + "\",\"content\":\"modified!\"}";
        StubHandler stub = new StubHandler(req -> ApprovalResult.modify(modifiedArgs));
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + original.toString().replace("\\", "\\\\") + "\",\"content\":\"oops\"}");

        assertFalse(result.startsWith("[HITL]"), "MODIFIED 应实际执行工具: " + result);
        assertFalse(Files.exists(original), "原始路径不应被写入");
        assertTrue(Files.exists(modified), "修改后的路径应被写入");
        assertEquals("modified!", Files.readString(modified));
    }

    @Test
    void modifiedWriteFinishesBeforeLaterReadOfEffectivePath(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("destination.txt"), "old-value");
        CountDownLatch readFinished = new CountDownLatch(1);
        StubHandler stub = new StubHandler(request -> {
            try {
                readFinished.await(300, TimeUnit.MILLISECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return ApprovalResult.reject("interrupted");
            }
            return ApprovalResult.modify("{\"path\":\"destination.txt\",\"content\":\"new-value\"}");
        });
        HitlToolRegistry registry = new HitlToolRegistry(stub) {
            @Override
            public ToolOutput executeToolOutput(String name, String arguments) {
                ToolOutput output = super.executeToolOutput(name, arguments);
                if ("read_file".equals(name)) {
                    readFinished.countDown();
                }
                return output;
            }
        };
        registry.setProjectPath(tempDir.toString());

        var results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("write", "write_file",
                        "{\"path\":\"original.txt\",\"content\":\"original\"}"),
                new ToolRegistry.ToolInvocation("read", "read_file",
                        "{\"path\":\"destination.txt\"}")));

        assertTrue(results.get(0).successful());
        assertTrue(results.get(1).result().contains("new-value"), results.get(1).result());
        assertEquals(List.of("write", "read"), results.stream().map(ToolRegistry.ToolExecutionResult::id).toList());
        assertEquals(1, stub.requestCount());
        assertFalse(Files.exists(tempDir.resolve("original.txt")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledOrPreapprovedHitlKeepsIndependentWritesParallel(boolean enabled, @TempDir Path tempDir) {
        StubHandler stub = new StubHandler(request -> {
            throw new AssertionError("unexpected approval");
        });
        stub.setEnabled(enabled);
        stub.approvedTools.add("write_file");
        CountDownLatch bothStarted = new CountDownLatch(2);
        HitlToolRegistry registry = new HitlToolRegistry(stub) {
            @Override
            public ToolOutput executeToolOutput(String name, String arguments) {
                bothStarted.countDown();
                try {
                    assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return super.executeToolOutput(name, arguments);
            }
        };
        registry.setProjectPath(tempDir.toString());

        var results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("first", "write_file", "{\"path\":\"first.txt\",\"content\":\"a\"}"),
                new ToolRegistry.ToolInvocation("second", "write_file", "{\"path\":\"second.txt\",\"content\":\"b\"}")));

        assertTrue(results.stream().allMatch(ToolRegistry.ToolExecutionResult::successful));
        assertEquals(0, stub.requestCount());
    }

    @Test
    void approvalRevokedAfterBatchSelectionFailsClosedBeforePrompt(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("destination.txt"), "old-value");
        AtomicInteger approvalChecks = new AtomicInteger();
        AtomicInteger approvalRequests = new AtomicInteger();
        HitlHandler handler = new HitlHandler() {
            @Override
            public ApprovalResult requestApproval(ApprovalRequest request) {
                approvalRequests.incrementAndGet();
                return ApprovalResult.modify("{\"path\":\"destination.txt\",\"content\":\"new-value\"}");
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public void setEnabled(boolean enabled) {
            }

            @Override
            public boolean isApprovedAllByTool(String toolName) {
                return approvalChecks.incrementAndGet() == 1;
            }
        };
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(tempDir.toString());

        var results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("write", "write_file", "{\"path\":\"original.txt\",\"content\":\"x\"}"),
                new ToolRegistry.ToolInvocation("read", "read_file", "{\"path\":\"destination.txt\"}")));

        assertFalse(results.get(0).successful());
        assertEquals(0, approvalRequests.get());
        assertEquals("old-value", Files.readString(tempDir.resolve("destination.txt")));
        assertFalse(Files.exists(tempDir.resolve("original.txt")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unifiedWebMcpBackendCanRequestApprovalAlongsideRead(boolean automatic, @TempDir Path tempDir)
            throws Exception {
        Files.writeString(tempDir.resolve("fixture.txt"), "fixture");
        StubHandler stub = new StubHandler(request -> ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());
        registerMcpTool(registry, "step_search", "web_search", arguments -> "search-result");
        if (automatic) {
            registry.setCurrentModel("step", "step-3.7-flash");
        } else {
            CodeAgentConfig.WebToolsConfig config = new CodeAgentConfig.WebToolsConfig();
            CodeAgentConfig.WebToolRouteConfig route = new CodeAgentConfig.WebToolRouteConfig();
            route.setBackend("mcp");
            route.setTool("mcp__step_search__web_search");
            config.setSearch(route);
            registry.setWebToolsConfig(config);
        }

        var results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("search", "web_search", "{\"query\":\"fixture\"}"),
                new ToolRegistry.ToolInvocation("read", "read_file", "{\"path\":\"fixture.txt\"}")));

        assertTrue(results.get(0).successful(), results.get(0).result());
        assertTrue(results.get(0).result().contains("search-result"));
        assertTrue(results.get(1).successful());
        assertEquals(1, stub.requestCount());
        assertEquals("mcp__step_search__web_search", stub.received.get(0).toolName());
    }

    @Test
    void unifiedFetchMcpBackendCanRequestApprovalAlongsideRead(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("fixture.txt"), "fixture");
        StubHandler stub = new StubHandler(request -> ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());
        registerMcpTool(registry, "fetch", "page", arguments -> "fetch-result");
        CodeAgentConfig.WebToolsConfig config = new CodeAgentConfig.WebToolsConfig();
        CodeAgentConfig.WebToolRouteConfig route = new CodeAgentConfig.WebToolRouteConfig();
        route.setBackend("mcp");
        route.setTool("mcp__fetch__page");
        config.setFetch(route);
        registry.setWebToolsConfig(config);

        var results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("fetch", "web_fetch", "{\"url\":\"https://8.8.8.8/fixture\"}"),
                new ToolRegistry.ToolInvocation("read", "read_file", "{\"path\":\"fixture.txt\"}")));

        assertTrue(results.get(0).successful(), results.get(0).result());
        assertTrue(results.get(0).result().contains("fetch-result"));
        assertEquals(1, stub.requestCount());
        assertEquals("mcp__fetch__page", stub.received.get(0).toolName());
    }

    @Test
    void approvedAllDecisionExecutesTool(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("approved-all.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.approveAll());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"ok\"}");

        assertFalse(result.startsWith("[HITL]"));
        assertTrue(Files.exists(target));
    }

    @Test
    void approvedAllByServerDecisionExecutesMcpTool() {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approveAllByServer());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registerMcpTool(registry, "chrome-devtools", "navigate_page", args -> "navigated");

        String result = registry.executeTool("mcp__chrome-devtools__navigate_page",
                "{\"url\":\"https://example.com\"}");

        assertEquals("navigated", result);
        assertEquals(1, stub.requestCount());
    }

    @Test
    void approvedAllByServerCacheSkipsApprovalForSameMcpServer() {
        StubHandler stub = new StubHandler(req -> {
            throw new AssertionError("server 维度已放行后不应再次触发审批");
        });
        stub.approveServer("chrome-devtools");
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registerMcpTool(registry, "chrome-devtools", "click", args -> "clicked");

        String result = registry.executeTool("mcp__chrome-devtools__click", "{\"uid\":\"1\"}");

        assertEquals("clicked", result);
        assertEquals(0, stub.requestCount());
    }

    @Test
    void sensitiveBrowserToolBypassesApprovedAllByServerCache(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(rules, "*://example.com/admin/*\n");
        BrowserSession session = new BrowserSession();
        session.switchToShared("http://127.0.0.1:9222");
        session.recordOpenedTab("page-1");
        session.rememberNavigation("https://example.com/admin/users");
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        stub.approveServer("chrome-devtools");
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setBrowserGuard(new BrowserGuard(session, new SensitivePagePolicy(rules)));
        registerMcpTool(registry, "chrome-devtools", "click", args -> "clicked");

        String result = registry.executeTool("mcp__chrome-devtools__click", "{\"uid\":\"1\"}");

        assertEquals("clicked", result);
        assertEquals(1, stub.requestCount());
        assertNotNull(stub.received.get(0).sensitiveNotice());
    }

    @Test
    void nonDangerousToolSkipsApprovalEvenWhenEnabled() {
        StubHandler stub = new StubHandler(req -> {
            throw new AssertionError("non-dangerous 工具不应触发审批");
        });
        HitlToolRegistry registry = new HitlToolRegistry(stub);

        String result = registry.executeTool("list_dir", "{\"path\":\".\"}");
        assertFalse(result.startsWith("[HITL]"));
        assertEquals(0, stub.requestCount());
    }

    /** 可预设决策结果的 HitlHandler stub。 */
    private static final class StubHandler implements HitlHandler {
        private final Function<ApprovalRequest, ApprovalResult> decision;
        private final List<ApprovalRequest> received = new ArrayList<>();
        private final List<String> approvedServers = new ArrayList<>();
        private final List<String> approvedTools = new ArrayList<>();
        private boolean enabled = true;

        StubHandler(Function<ApprovalRequest, ApprovalResult> decision) {
            this.decision = decision;
        }

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            received.add(request);
            return decision.apply(request);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        int requestCount() {
            return received.size();
        }

        void approveServer(String serverName) {
            approvedServers.add(serverName);
        }

        @Override
        public boolean isApprovedAllByTool(String toolName) {
            return approvedTools.contains(toolName);
        }

        @Override
        public boolean isApprovedAllByServer(String serverName) {
            return approvedServers.contains(serverName);
        }
    }

    private static void registerMcpTool(HitlToolRegistry registry, String serverName, String toolName,
                                        Function<String, String> invoker) {
        registry.registerMcpTool(new McpToolDescriptor(
                serverName,
                toolName,
                McpToolDescriptor.namespaced(serverName, toolName),
                "test tool",
                JsonNodeFactory.instance.objectNode()
        ), invoker);
    }
}
