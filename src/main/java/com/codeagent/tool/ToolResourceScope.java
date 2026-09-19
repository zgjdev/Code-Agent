package com.codeagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeagent.tool.ToolRegistry.ToolInvocation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Task-local project resource restrictions. This class grants no underlying tool permission. */
public record ToolResourceScope(Path projectRoot,
                                List<Path> readableRoots,
                                List<Path> writableRoots,
                                boolean workspaceWrite) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> WORKSPACE_MUTATION_TOOLS = Set.of(
            "execute_command", "create_project", "revert_turn");

    public ToolResourceScope {
        projectRoot = (projectRoot == null ? Path.of(".") : projectRoot).toAbsolutePath().normalize();
        readableRoots = normalizeRoots(projectRoot, readableRoots);
        writableRoots = normalizeRoots(projectRoot, writableRoots);
        if (writableRoots.stream().anyMatch(projectRoot::equals)) {
            workspaceWrite = true;
        }
    }

    public boolean exposes(String toolName) {
        if ("write_file".equals(toolName)) {
            return workspaceWrite || !writableRoots.isEmpty();
        }
        if (WORKSPACE_MUTATION_TOOLS.contains(toolName)) {
            return workspaceWrite;
        }
        return true;
    }

    public Optional<String> denialReason(ToolInvocation invocation) {
        if (invocation == null || invocation.name() == null) {
            return Optional.of("invalid tool invocation");
        }
        if (WORKSPACE_MUTATION_TOOLS.contains(invocation.name()) && !workspaceWrite) {
            return Optional.of("tool requires an exclusive workspaceWrite claim");
        }
        if (!"write_file".equals(invocation.name()) || workspaceWrite) {
            return Optional.empty();
        }
        if (writableRoots.isEmpty()) {
            return Optional.of("task declared no writable project path");
        }

        String rawPath = jsonText(invocation.argumentsJson(), "path");
        if (rawPath.isBlank()) {
            return Optional.of("write_file path is missing or invalid");
        }
        Path target;
        try {
            Path supplied = Path.of(rawPath);
            target = (supplied.isAbsolute() ? supplied : projectRoot.resolve(supplied))
                    .toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Optional.of("write_file path is invalid");
        }
        if (!target.startsWith(projectRoot)) {
            return Optional.of("write_file path escapes the project root");
        }
        boolean allowed = writableRoots.stream().anyMatch(target::startsWith);
        return allowed ? Optional.empty() : Optional.of("write_file path is outside declared writePaths");
    }

    private static List<Path> normalizeRoots(Path projectRoot, List<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            return List.of();
        }
        List<Path> normalized = new ArrayList<>();
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            Path candidate = (root.isAbsolute() ? root : projectRoot.resolve(root))
                    .toAbsolutePath().normalize();
            if (!candidate.startsWith(projectRoot)) {
                throw new IllegalArgumentException("resource root escapes the project root");
            }
            if (!normalized.contains(candidate)) {
                normalized.add(candidate);
            }
        }
        return List.copyOf(normalized);
    }

    private static String jsonText(String argumentsJson, String field) {
        try {
            JsonNode root = MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
            return root.path(field).asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
