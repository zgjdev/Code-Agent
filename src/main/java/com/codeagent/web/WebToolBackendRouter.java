package com.codeagent.web;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.mcp.protocol.McpToolDescriptor;
import com.codeagent.tool.ToolOutput;

import java.util.Set;

/** Resolves stable Web tools to configured backends and controls model visibility. */
public final class WebToolBackendRouter {
    private static final Set<String> RESERVED_MCP_TOOL_NAMES = Set.of("web_search", "web_fetch");
    public static final String ANYSEARCH_TOOL = "mcp__anysearch__search";
    public static final String ANYSEARCH_FETCH_TOOL = "mcp__anysearch__extract";
    public static final String STEP_SEARCH_TOOL = "mcp__step_search__web_search";
    public static final String STEP_FETCH_TOOL = "mcp__step_search__web_fetch";

    private final Route searchRoute;
    private final Route fetchRoute;

    public WebToolBackendRouter(CodeAgentConfig.WebToolsConfig config) {
        CodeAgentConfig.WebToolsConfig effective = config == null
                ? new CodeAgentConfig.WebToolsConfig()
                : config;
        this.searchRoute = Route.from("web_search", effective.getSearch());
        this.fetchRoute = Route.from("web_fetch", effective.getFetch());
    }

    public Route searchRoute(String provider, String model) {
        return resolve(searchRoute, ANYSEARCH_TOOL);
    }

    public Route fetchRoute(String provider, String model) {
        return resolve(fetchRoute, ANYSEARCH_FETCH_TOOL);
    }

    private static Route resolve(Route configured, String defaultTool) {
        return new Route(configured.logicalTool(), "mcp", null,
                configured.usesAuto() ? defaultTool : configured.tool(),
                configured.onUnavailable(), configured.validationError());
    }

    public String fallbackTool(Route route) {
        return "web_search".equals(route.logicalTool()) ? STEP_SEARCH_TOOL : STEP_FETCH_TOOL;
    }

    public java.util.List<String> executionTools(Route route) {
        if (!route.valid()) return java.util.List.of();
        return route.fallbackToStep() && !fallbackTool(route).equals(route.tool())
                ? java.util.List.of(route.tool(), fallbackTool(route)) : java.util.List.of(route.tool());
    }

    public boolean isModelVisible(String registeredName, McpToolDescriptor descriptor) {
        if (descriptor == null) {
            return true;
        }
        if ("anysearch".equals(descriptor.serverName()) || RESERVED_MCP_TOOL_NAMES.contains(descriptor.name())) {
            return false;
        }
        return !isConfiguredMcpBackend(registeredName, searchRoute)
                && !isConfiguredMcpBackend(registeredName, fetchRoute);
    }

    private static boolean isConfiguredMcpBackend(String registeredName, Route route) {
        return route.usesMcp() && registeredName.equals(route.tool());
    }

    public boolean mayFallback(Route route, ToolOutput output) {
        return route != null
                && route.valid()
                && !fallbackTool(route).equals(route.tool())
                && route.fallbackToStep()
                && output != null
                && output.failureKind() == ToolOutput.FailureKind.BACKEND_UNAVAILABLE;
    }

    public record Route(String logicalTool,
                        String backend,
                        String provider,
                        String tool,
                        String onUnavailable,
                        String validationError) {
        static Route from(String logicalTool, CodeAgentConfig.WebToolRouteConfig config) {
            CodeAgentConfig.WebToolRouteConfig effective = config == null
                    ? CodeAgentConfig.WebToolRouteConfig.autoDefault()
                    : config;
            return new Route(logicalTool, effective.getBackend(), effective.getProvider(), effective.getTool(),
                    effective.getOnUnavailable(), effective.validationError(logicalTool));
        }

        public boolean usesMcp() {
            return "mcp".equals(backend);
        }

        public boolean usesAuto() {
            return "auto".equals(backend);
        }

        public boolean fallbackToStep() {
            return "step".equals(onUnavailable) || "default".equals(onUnavailable);
        }

        public boolean valid() {
            return validationError == null;
        }
    }
}
