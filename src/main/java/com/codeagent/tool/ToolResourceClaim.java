package com.codeagent.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Deterministic resources touched by one tool invocation.
 *
 * <p>Claims are derived locally from the tool name and actual call arguments.
 * They are scheduling metadata only and never grant additional authority.</p>
 */
public record ToolResourceClaim(List<Path> readPaths,
                                List<Path> writePaths,
                                boolean workspaceRead,
                                boolean workspaceWrite,
                                Set<String> exclusiveResources) {

    public ToolResourceClaim {
        readPaths = readPaths == null ? List.of() : List.copyOf(readPaths);
        writePaths = writePaths == null ? List.of() : List.copyOf(writePaths);
        exclusiveResources = exclusiveResources == null ? Set.of() : Set.copyOf(exclusiveResources);
    }

    public static ToolResourceClaim none() {
        return new ToolResourceClaim(List.of(), List.of(), false, false, Set.of());
    }

    public static ToolResourceClaim read(Path path) {
        return new ToolResourceClaim(List.of(path), List.of(), false, false, Set.of());
    }

    public static ToolResourceClaim write(Path path) {
        return new ToolResourceClaim(List.of(), List.of(path), false, false, Set.of());
    }

    public static ToolResourceClaim forWorkspaceRead() {
        return new ToolResourceClaim(List.of(), List.of(), true, false, Set.of());
    }

    public static ToolResourceClaim forWorkspaceWrite() {
        return new ToolResourceClaim(List.of(), List.of(), false, true, Set.of());
    }

    public static ToolResourceClaim exclusive(String resource) {
        return new ToolResourceClaim(List.of(), List.of(), false, false, Set.of(resource));
    }

    public boolean touchesWorkspace() {
        return workspaceRead || workspaceWrite || !readPaths.isEmpty() || !writePaths.isEmpty();
    }
}
