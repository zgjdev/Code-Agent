package com.codeagent.web;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.mcp.protocol.McpToolDescriptor;
import com.codeagent.tool.ToolOutput;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebToolBackendRouterTest {

    @Test
    void resolvesAutoRoutesFromCurrentModel() {
        WebToolBackendRouter router = new WebToolBackendRouter(new CodeAgentConfig.WebToolsConfig());

        assertEquals("mcp", router.searchRoute("step", "step-3.7-flash").backend());
        assertEquals("mcp__anysearch__search",
                router.searchRoute("step", "step-3.7-flash").tool());
        assertEquals("mcp", router.fetchRoute("step", "step-3.7-flash-202609").backend());
        assertEquals("mcp", router.searchRoute("glm", "glm-4.5").backend());
        assertEquals("mcp__anysearch__extract", router.fetchRoute("glm", "glm-4.5").tool());
    }

    @Test
    void explicitRoutesOverrideModelAwareSelection() {
        CodeAgentConfig.WebToolsConfig config = new CodeAgentConfig.WebToolsConfig();
        CodeAgentConfig.WebToolRouteConfig search = new CodeAgentConfig.WebToolRouteConfig();
        search.setBackend("provider");
        search.setProvider("searxng");
        config.setSearch(search);
        WebToolBackendRouter router = new WebToolBackendRouter(config);

        assertFalse(router.searchRoute("step", "step-3.7-flash").valid());
    }

    @Test
    void hidesReservedAndConfiguredMcpBackends() {
        CodeAgentConfig.WebToolsConfig config = new CodeAgentConfig.WebToolsConfig();
        CodeAgentConfig.WebToolRouteConfig search = new CodeAgentConfig.WebToolRouteConfig();
        search.setBackend("mcp");
        search.setTool("mcp__custom__lookup");
        config.setSearch(search);
        WebToolBackendRouter router = new WebToolBackendRouter(config);

        assertFalse(router.isModelVisible("mcp__anysearch__search",
                descriptor("step_search", "web_search")));
        assertFalse(router.isModelVisible("mcp__custom__lookup",
                descriptor("custom", "lookup")));
        assertTrue(router.isModelVisible("mcp__custom__other",
                descriptor("custom", "other")));
        assertTrue(router.isModelVisible("web_search", null));
        assertTrue(router.isModelVisible("web_fetch", null));
    }

    @Test
    void fallbackRequiresExplicitDefaultAndBackendUnavailable() {
        CodeAgentConfig.WebToolsConfig config = new CodeAgentConfig.WebToolsConfig();
        CodeAgentConfig.WebToolRouteConfig search = new CodeAgentConfig.WebToolRouteConfig();
        search.setBackend("mcp");
        search.setTool("mcp__anysearch__search");
        search.setOnUnavailable("default");
        config.setSearch(search);
        WebToolBackendRouter router = new WebToolBackendRouter(config);

        assertTrue(router.mayFallback(router.searchRoute("glm", "glm-4.5"), ToolOutput.failure(
                ToolOutput.FailureKind.BACKEND_UNAVAILABLE, "not ready")));
        assertFalse(router.mayFallback(router.searchRoute("glm", "glm-4.5"), ToolOutput.failure(
                ToolOutput.FailureKind.POLICY_DENIED, "denied")));
        assertFalse(router.mayFallback(router.searchRoute("glm", "glm-4.5"), ToolOutput.failure(
                ToolOutput.FailureKind.HITL_REJECTED, "rejected")));
        assertFalse(router.mayFallback(router.searchRoute("glm", "glm-4.5"), ToolOutput.failure(
                ToolOutput.FailureKind.CANCELLED, "cancelled")));
    }

    @Test
    void failClosedRouteNeverFallsBack() {
        CodeAgentConfig.WebToolsConfig config = new CodeAgentConfig.WebToolsConfig();
        CodeAgentConfig.WebToolRouteConfig fetch = new CodeAgentConfig.WebToolRouteConfig();
        fetch.setBackend("mcp");
        fetch.setTool("mcp__step_search__web_fetch");
        config.setFetch(fetch);
        WebToolBackendRouter router = new WebToolBackendRouter(config);

        assertFalse(router.mayFallback(router.fetchRoute("glm", "glm-4.5"), ToolOutput.failure(
                ToolOutput.FailureKind.BACKEND_UNAVAILABLE, "not ready")));
    }

    private static McpToolDescriptor descriptor(String server, String name) {
        return new McpToolDescriptor(server, name,
                McpToolDescriptor.namespaced(server, name), name,
                JsonNodeFactory.instance.objectNode());
    }
}
