package com.codeagent.plan;

import java.util.ArrayList;
import java.util.List;

/** Selects a stable, bounded batch whose declared project resources do not conflict. */
public final class ConflictAwareBatchSelector {

    public List<Task> select(List<Task> readyInExecutionOrder, int maxConcurrency) {
        if (readyInExecutionOrder == null || readyInExecutionOrder.isEmpty()) {
            return List.of();
        }

        int limit = Math.max(1, maxConcurrency);
        List<Task> selected = new ArrayList<>();
        for (Task candidate : readyInExecutionOrder) {
            if (selected.size() >= limit) {
                break;
            }
            boolean compatible = selected.stream().noneMatch(existing -> conflicts(
                    existing.getResourceClaims(), candidate.getResourceClaims()));
            if (compatible) {
                selected.add(candidate);
            }
        }
        if (selected.isEmpty()) {
            return List.of(readyInExecutionOrder.get(0));
        }
        return List.copyOf(selected);
    }

    public boolean conflicts(TaskResourceClaims left, TaskResourceClaims right) {
        TaskResourceClaims safeLeft = left == null
                ? new TaskResourceClaims(List.of(), List.of(), true)
                : left;
        TaskResourceClaims safeRight = right == null
                ? new TaskResourceClaims(List.of(), List.of(), true)
                : right;
        return safeLeft.workspaceWrite()
                || safeRight.workspaceWrite()
                || overlaps(safeLeft.writePaths(), safeRight.writePaths())
                || overlaps(safeLeft.writePaths(), safeRight.readPaths())
                || overlaps(safeRight.writePaths(), safeLeft.readPaths());
    }

    private boolean overlaps(List<String> leftPaths, List<String> rightPaths) {
        for (String left : leftPaths) {
            for (String right : rightPaths) {
                if (overlaps(left, right)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean overlaps(String left, String right) {
        String normalizedLeft = trimDirectoryMarker(left);
        String normalizedRight = trimDirectoryMarker(right);
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) {
            return false;
        }
        return normalizedLeft.equals(normalizedRight)
                || normalizedLeft.startsWith(normalizedRight + "/")
                || normalizedRight.startsWith(normalizedLeft + "/");
    }

    private String trimDirectoryMarker(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
