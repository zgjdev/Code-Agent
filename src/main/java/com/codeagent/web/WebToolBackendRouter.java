package com.codeagent.web;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.mcp.protocol.McpToolDescriptor;
import com.codeagent.tool.ToolOutput;

import java.util.Set;

/** Resolves stable Web tools to configured backends and controls model visibility. */
public final class WebToolBackendRouter {
    private static final Set<String> RESERVED_MCP_TOOL_NAMES = Set.of("web_search", "web_fetch");
    private static final String STEP_SEARCH_TOOL = "mcp__step_search__web_search";
    private static final String STEP_FETCH_TOOL = "mcp__step_search__web_fetch";

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
        return resolveAuto(searchRoute, provider, model);
    }

    public Route fetchRoute(String provider, String model) {
        return resolveAuto(fetchRoute, provider, model);
    }

    public boolean isModelVisible(String registeredName, McpToolDescriptor descriptor) {
        if (descriptor == null) {
            return true;
        }
        if (RESERVED_MCP_TOOL_NAMES.contains(descriptor.name())) {
            return false;
        }
        return !isConfiguredMcpBackend(registeredName, searchRoute)
                && !isConfiguredMcpBackend(registeredName, fetchRoute);
    }

    private static boolean isConfiguredMcpBackend(String registeredName, Route route) {
        return route.usesMcp() && registeredName.equals(route.tool());
    }

    private Route resolveAuto(Route configured, String provider, String model) {
        if (!configured.usesAuto()) {
            return configured;
        }
        if (isStepSearchModel(provider, model)) {
            return new Route(configured.logicalTool(), "mcp", null,
                    "web_search".equals(configured.logicalTool()) ? STEP_SEARCH_TOOL : STEP_FETCH_TOOL,
                    "default", null);
        }
        return new Route(configured.logicalTool(),
                "web_search".equals(configured.logicalTool()) ? "provider" : "direct",
                configured.provider(), null, "fail", null);
    }

    private static boolean isStepSearchModel(String provider, String model) {
        return provider != null && "step".equalsIgnoreCase(provider.trim())
                && model != null && model.trim().toLowerCase(java.util.Locale.ROOT)
                .startsWith("step-3.7-flash");
    }

    public boolean mayFallback(Route route, ToolOutput output) {
        return route != null
                && route.fallbackToDefault()
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

        public boolean fallbackToDefault() {
            return "default".equals(onUnavailable);
        }

        public boolean valid() {
            return validationError == null;
        }
    }
}
