package com.codeagent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResourceClaimsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path projectRoot;

    @Test
    void normalizesProjectRelativeFilesAndDirectories() throws Exception {
        JsonNode resources = mapper.readTree("""
                {
                  "readPaths": ["src\\\\main\\\\java\\\\"],
                  "writePaths": ["src/main/java/com/acme/Service.java"],
                  "workspaceWrite": false
                }
                """);

        TaskResourceClaims claims = TaskResourceClaims.normalize(
                projectRoot, Task.TaskType.FILE_WRITE, resources);

        assertEquals(List.of("src/main/java/"), claims.readPaths());
        assertEquals(List.of("src/main/java/com/acme/Service.java"), claims.writePaths());
        assertFalse(claims.workspaceWrite());
    }

    @Test
    void rejectsAbsoluteAndParentTraversalPaths() throws Exception {
        JsonNode absolute = mapper.readTree("""
                {"readPaths": ["C:/outside.txt"]}
                """);
        JsonNode traversal = mapper.readTree("""
                {"writePaths": ["../outside.txt"]}
                """);

        assertThrows(IllegalArgumentException.class,
                () -> TaskResourceClaims.normalize(projectRoot, Task.TaskType.FILE_READ, absolute));
        assertThrows(IllegalArgumentException.class,
                () -> TaskResourceClaims.normalize(projectRoot, Task.TaskType.FILE_WRITE, traversal));
    }

    @Test
    void missingWriteClaimsFallBackToWorkspaceExclusivity() {
        TaskResourceClaims claims = TaskResourceClaims.normalize(
                projectRoot, Task.TaskType.FILE_WRITE, null);

        assertTrue(claims.workspaceWrite());
        assertTrue(claims.writePaths().isEmpty());
    }

    @Test
    void readOnlyDefaultsDoNotGrantWorkspaceWrites() {
        TaskResourceClaims claims = TaskResourceClaims.conservativeDefault(Task.TaskType.FILE_READ);

        assertFalse(claims.workspaceWrite());
        assertTrue(claims.readPaths().isEmpty());
        assertTrue(claims.writePaths().isEmpty());
    }
}
