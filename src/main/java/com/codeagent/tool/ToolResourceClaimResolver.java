package com.codeagent.tool;

import com.codeagent.policy.PathGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Derives scheduling resources from a concrete tool invocation without asking the LLM.
 */
public final class ToolResourceClaimResolver {
    static final String RESOURCE_BROWSER_SESSION = "browser-session";
    static final String RESOURCE_MCP_EXTERNAL = "mcp-external";
    static final String RESOURCE_SKILL_CONTEXT = "skill-context";
    static final String RESOURCE_LONG_TERM_MEMORY = "long-term-memory";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PathGuard pathGuard;

    public ToolResourceClaimResolver(PathGuard pathGuard) {
        this.pathGuard = Objects.requireNonNull(pathGuard, "pathGuard");
    }

    public ToolResourceClaim resolve(ToolRegistry.ToolInvocation invocation) {
        if (invocation == null) {
            return ToolResourceClaim.none();
        }
        return resolve(invocation.name(), invocation.argumentsJson());
    }

    public ToolResourceClaim resolve(String toolName, String argumentsJson) {
        if (toolName == null || toolName.isBlank()) {
            return ToolResourceClaim.none();
        }
        return switch (toolName) {
            case "read_file" -> resolveReadPath(argumentsJson, "path", false);
            case "write_file" -> resolveWritePath(argumentsJson, "path");
            case "list_dir" -> resolveReadPath(argumentsJson, "path", false);
            case "glob_files", "grep_code" -> resolveReadPath(argumentsJson, "path", true);
            case "search_code" -> ToolResourceClaim.forWorkspaceRead();
            case "execute_command", "revert_turn" -> ToolResourceClaim.forWorkspaceWrite();
            case "create_project" -> resolveWritePath(argumentsJson, "name");
            case "web_search", "web_fetch" -> ToolResourceClaim.none();
            case "load_skill" -> ToolResourceClaim.exclusive(RESOURCE_SKILL_CONTEXT);
            case "save_memory" -> ToolResourceClaim.exclusive(RESOURCE_LONG_TERM_MEMORY);
            case "browser_connect", "browser_disconnect", "browser_status" ->
                    ToolResourceClaim.exclusive(RESOURCE_BROWSER_SESSION);
            default -> resolveUnknown(toolName);
        };
    }

    private ToolResourceClaim resolveUnknown(String toolName) {
        if (TurnToolPolicy.isBrowserToolName(toolName)) {
            return ToolResourceClaim.exclusive(RESOURCE_BROWSER_SESSION);
        }
        if (toolName.startsWith("mcp__")) {
            return ToolResourceClaim.exclusive(RESOURCE_MCP_EXTERNAL);
        }
        return ToolResourceClaim.none();
    }

    private ToolResourceClaim resolveReadPath(String argumentsJson, String field, boolean defaultWorkspace) {
        String raw = argument(argumentsJson, field);
        if (raw == null || raw.isBlank() || (defaultWorkspace && ".".equals(raw.trim()))) {
            return ToolResourceClaim.forWorkspaceRead();
        }
        try {
            Path safe = pathGuard.resolveSafe(raw);
            return ToolResourceClaim.read(safe);
        } catch (RuntimeException e) {
            return ToolResourceClaim.forWorkspaceRead();
        }
    }

    private ToolResourceClaim resolveWritePath(String argumentsJson, String field) {
        String raw = argument(argumentsJson, field);
        if (raw == null || raw.isBlank()) {
            return ToolResourceClaim.forWorkspaceWrite();
        }
        try {
            Path safe = pathGuard.resolveSafe(raw);
            return ToolResourceClaim.write(safe);
        } catch (RuntimeException e) {
            return ToolResourceClaim.forWorkspaceWrite();
        }
    }

    private String argument(String argumentsJson, String field) {
        try {
            JsonNode args = MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank()
                    ? "{}"
                    : argumentsJson);
            if (args == null || !args.isObject()) {
                return null;
            }
            JsonNode value = args.get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
