package com.codeagent.plan;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable, normalized project resources declared by a plan task. */
public record TaskResourceClaims(List<String> readPaths,
                                 List<String> writePaths,
                                 boolean workspaceWrite) {
    private static final Set<Character> GLOB_CHARACTERS = Set.of('*', '?', '[', ']', '{', '}');

    public TaskResourceClaims {
        readPaths = readPaths == null ? List.of() : List.copyOf(readPaths);
        writePaths = writePaths == null ? List.of() : List.copyOf(writePaths);
    }

    public static TaskResourceClaims conservativeDefault(Task.TaskType type) {
        boolean exclusive = type == Task.TaskType.FILE_WRITE
                || type == Task.TaskType.COMMAND
                || type == Task.TaskType.PLANNING;
        return new TaskResourceClaims(List.of(), List.of(), exclusive);
    }

    public static TaskResourceClaims normalize(Path projectRoot,
                                               Task.TaskType type,
                                               JsonNode resources) {
        if (resources == null || resources.isMissingNode() || resources.isNull()) {
            return conservativeDefault(type);
        }
        if (!resources.isObject()) {
            throw new IllegalArgumentException("resources must be an object");
        }

        Path root = (projectRoot == null ? Path.of(".") : projectRoot).toAbsolutePath().normalize();
        List<String> reads = normalizePaths(root, resources.path("readPaths"), "readPaths");
        List<String> writes = normalizePaths(root, resources.path("writePaths"), "writePaths");
        boolean workspaceWrite = resources.path("workspaceWrite").asBoolean(false);
        if (type == Task.TaskType.FILE_WRITE && writes.isEmpty()) {
            workspaceWrite = true;
        }
        if ((type == Task.TaskType.COMMAND || type == Task.TaskType.PLANNING)
                && !resources.has("workspaceWrite")) {
            workspaceWrite = true;
        }
        return new TaskResourceClaims(reads, writes, workspaceWrite);
    }

    private static List<String> normalizePaths(Path root, JsonNode paths, String fieldName) {
        if (paths == null || paths.isMissingNode() || paths.isNull()) {
            return List.of();
        }
        if (!paths.isArray()) {
            throw new IllegalArgumentException(fieldName + " must be an array");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (JsonNode pathNode : paths) {
            if (!pathNode.isTextual()) {
                throw new IllegalArgumentException(fieldName + " entries must be strings");
            }
            normalized.add(normalizePath(root, pathNode.asText(), fieldName));
        }
        return List.copyOf(normalized);
    }

    private static String normalizePath(Path root, String value, String fieldName) {
        String raw = value == null ? "" : value.trim().replace('\\', '/');
        if (raw.isEmpty() || raw.equals(".")) {
            throw new IllegalArgumentException(fieldName + " cannot contain the project root");
        }
        if (raw.startsWith("/") || raw.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException(fieldName + " must contain project-relative paths");
        }
        for (String segment : raw.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException(fieldName + " cannot contain '..'");
            }
        }
        if (raw.chars().anyMatch(ch -> GLOB_CHARACTERS.contains((char) ch))) {
            throw new IllegalArgumentException(fieldName + " does not support glob paths");
        }

        boolean directory = raw.endsWith("/");
        Path resolved = root.resolve(raw).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(fieldName + " escapes the project root");
        }
        rejectExistingSymlinkEscape(root, resolved, fieldName);
        String relative = root.relativize(resolved).toString().replace('\\', '/');
        if (relative.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot contain the project root");
        }
        return directory && !relative.endsWith("/") ? relative + "/" : relative;
    }

    private static void rejectExistingSymlinkEscape(Path root, Path resolved, String fieldName) {
        try {
            Path realRoot = root.toRealPath();
            Path candidate = resolved;
            while (candidate != null && !Files.exists(candidate)) {
                candidate = candidate.getParent();
            }
            if (candidate == null || !candidate.toRealPath().startsWith(realRoot)) {
                throw new IllegalArgumentException(fieldName + " resolves outside the project root");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot validate " + fieldName, e);
        }
    }
}
