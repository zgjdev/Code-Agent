package com.codeagent.tool;

import com.codeagent.policy.PathGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResourceClaimResolverTest {

    @TempDir
    Path projectRoot;

    @Test
    void derivesExactReadAndWritePathsFromArguments() {
        ToolResourceClaimResolver resolver = resolver();

        ToolResourceClaim read = resolver.resolve(
                "read_file", "{\"path\":\"src/App.java\"}");
        ToolResourceClaim write = resolver.resolve(
                "write_file", "{\"path\":\"src/App.java\",\"content\":\"x\"}");

        Path expected = projectRoot.resolve("src/App.java").toAbsolutePath().normalize();
        assertEquals(List.of(expected), read.readPaths());
        assertEquals(List.of(expected), write.writePaths());
        assertFalse(read.workspaceRead());
        assertFalse(write.workspaceWrite());
    }

    @Test
    void commandUsesConservativeWorkspaceWriteClaim() {
        ToolResourceClaim claim = resolver().resolve(
                "execute_command", "{\"command\":\"mvn test\"}");

        assertTrue(claim.workspaceWrite());
        assertTrue(claim.touchesWorkspace());
    }

    @Test
    void projectWideSearchUsesWorkspaceReadClaim() {
        ToolResourceClaim claim = resolver().resolve(
                "search_code", "{\"query\":\"router\"}");

        assertTrue(claim.workspaceRead());
        assertFalse(claim.workspaceWrite());
    }

    @Test
    void invalidWritePathFallsBackToWorkspaceWrite() {
        ToolResourceClaim claim = resolver().resolve(
                "write_file", "{\"path\":\"../outside.txt\",\"content\":\"x\"}");

        assertTrue(claim.workspaceWrite());
        assertTrue(claim.writePaths().isEmpty());
    }

    @Test
    void unknownMcpToolIsConservativelySerializedInExternalDomain() {
        ToolResourceClaim claim = resolver().resolve(
                "mcp__github__create_issue", "{\"title\":\"x\"}");

        assertTrue(claim.exclusiveResources().contains(
                ToolResourceClaimResolver.RESOURCE_MCP_EXTERNAL));
    }

    private ToolResourceClaimResolver resolver() {
        return new ToolResourceClaimResolver(new PathGuard(projectRoot.toString()));
    }
}
