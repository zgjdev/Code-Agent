package com.codeagent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Splits one LLM turn's tool calls into stable, conflict-free execution batches.
 */
public final class ToolConflictAwareBatchSelector {
    private final ToolResourceClaimResolver resolver;

    public ToolConflictAwareBatchSelector(ToolResourceClaimResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public List<List<ToolRegistry.ToolInvocation>> partition(
            List<ToolRegistry.ToolInvocation> invocations,
            int maxConcurrency) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }

        List<List<ToolRegistry.ToolInvocation>> batches = new ArrayList<>();
        int index = 0;
        while (index < invocations.size()) {
            List<ToolRegistry.ToolInvocation> batch = selectNextBatch(
                    invocations.subList(index, invocations.size()), maxConcurrency);
            batches.add(batch);
            index += batch.size();
        }
        return List.copyOf(batches);
    }

    public List<ToolRegistry.ToolInvocation> selectNextBatch(
            List<ToolRegistry.ToolInvocation> invocations,
            int maxConcurrency) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, maxConcurrency);
        List<ClaimedInvocation> claimedBatch = new ArrayList<>();
        for (ToolRegistry.ToolInvocation invocation : invocations) {
            if (claimedBatch.size() >= limit) {
                break;
            }
            ToolResourceClaim claim = resolver.resolve(invocation);
            boolean compatible = claimedBatch.stream()
                    .noneMatch(existing -> conflicts(existing.claim(), claim));
            if (!claimedBatch.isEmpty() && !compatible) {
                break;
            }
            claimedBatch.add(new ClaimedInvocation(invocation, claim));
        }
        return claimedBatch.stream().map(ClaimedInvocation::invocation).toList();
    }

    public boolean conflicts(ToolResourceClaim left, ToolResourceClaim right) {
        ToolResourceClaim safeLeft = left == null ? ToolResourceClaim.none() : left;
        ToolResourceClaim safeRight = right == null ? ToolResourceClaim.none() : right;

        if (safeLeft.exclusiveResources().stream().anyMatch(safeRight.exclusiveResources()::contains)) {
            return true;
        }

        boolean leftWorkspace = safeLeft.touchesWorkspace();
        boolean rightWorkspace = safeRight.touchesWorkspace();
        if ((safeLeft.workspaceWrite() && rightWorkspace)
                || (safeRight.workspaceWrite() && leftWorkspace)) {
            return true;
        }
        if ((safeLeft.workspaceRead()
                && (safeRight.workspaceWrite() || !safeRight.writePaths().isEmpty()))
                || (safeRight.workspaceRead()
                && (safeLeft.workspaceWrite() || !safeLeft.writePaths().isEmpty()))) {
            return true;
        }

        return overlaps(safeLeft.writePaths(), safeRight.writePaths())
                || overlaps(safeLeft.writePaths(), safeRight.readPaths())
                || overlaps(safeRight.writePaths(), safeLeft.readPaths());
    }

    private boolean overlaps(List<Path> leftPaths, List<Path> rightPaths) {
        for (Path left : leftPaths) {
            for (Path right : rightPaths) {
                if (overlaps(left, right)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean overlaps(Path left, Path right) {
        if (left == null || right == null) {
            return false;
        }
        Path normalizedLeft = left.toAbsolutePath().normalize();
        Path normalizedRight = right.toAbsolutePath().normalize();
        if (normalizedLeft.equals(normalizedRight)
                || normalizedLeft.startsWith(normalizedRight)
                || normalizedRight.startsWith(normalizedLeft)) {
            return true;
        }
        try {
            if (Files.notExists(normalizedLeft) || Files.notExists(normalizedRight)) {
                return false;
            }
            return Files.isSameFile(normalizedLeft, normalizedRight);
        } catch (IOException | SecurityException failure) {
            return true;
        }
    }

    private record ClaimedInvocation(ToolRegistry.ToolInvocation invocation,
                                     ToolResourceClaim claim) {
    }
}
