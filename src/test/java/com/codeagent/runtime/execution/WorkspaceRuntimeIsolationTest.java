package com.codeagent.runtime.execution;

import com.codeagent.history.SessionStore;
import com.codeagent.llm.GLMClient;
import com.codeagent.mcp.protocol.McpToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceRuntimeIsolationTest {
    @Test void mutableDependenciesAndDynamicToolsStayWorkspaceLocal(@TempDir Path temporary) throws Exception {
        Path a = Files.createDirectory(temporary.resolve("a")); Path b = Files.createDirectory(temporary.resolve("b"));
        try (var sessions = SessionStore.open(temporary.resolve("history"));
             var store = new RuntimeExecutionStore(temporary.resolve("execution.db"))) {
            var factory = new DefaultWorkspaceRuntimeFactory(store, sessions, identity -> new GLMClient("test-key"));
            var resolver = new WorkspaceIdentityResolver();
            var runtimeA = factory.create(resolver.resolve(a), ExecutionEventSink.discarding());
            try (var runtimeB = factory.create(resolver.resolve(b), ExecutionEventSink.discarding())) {
                try {
                    String sa = runtimeA.createSession(); String sb = runtimeB.createSession();
                    try (var ca = runtimeA.contexts().acquire(sa); var cb = runtimeB.contexts().acquire(sb)) {
                        var aa = ca.context().agent(); var ab = cb.context().agent();
                        assertNotSame(aa.getToolRegistry(), ab.getToolRegistry());
                        assertNotSame(aa.getMemoryManager(), ab.getMemoryManager());
                        assertNotSame(ca.context().skillContextBuffer(), cb.context().skillContextBuffer());
                        assertNotSame(aa.getParentConversationContext(), ab.getParentConversationContext());
                        assertEquals(a.toRealPath().toString(), aa.getToolRegistry().getProjectPath());
                        assertEquals(b.toRealPath().toString(), ab.getToolRegistry().getProjectPath());
                        assertNotSame(runtimeA.broker(), runtimeB.broker());
                        assertNotSame(aa.getToolRegistry().getSnapshotService(), ab.getToolRegistry().getSnapshotService());
                        aa.getToolRegistry().registerMcpTool(new McpToolDescriptor("isolated", "local", "mcp__isolated__local",
                                "test", new ObjectMapper().createObjectNode().put("type", "object")), ignored -> "local");
                        assertTrue(aa.getToolRegistry().getToolDefinitions().stream().anyMatch(t -> t.name().equals("mcp__isolated__local")));
                        assertFalse(ab.getToolRegistry().getToolDefinitions().stream().anyMatch(t -> t.name().equals("mcp__isolated__local")));
                        assertThrows(IllegalStateException.class, runtimeA::claimAllowed);
                        assertTrue(runtimeB.claimAllowed());
                    }
                } finally { runtimeA.close(); }
                String anotherB = runtimeB.createSession();
                try (var lease = runtimeB.contexts().acquire(anotherB)) {
                    assertEquals(b.toRealPath().toString(), lease.context().agent().getToolRegistry().getProjectPath());
                }
            }
        }
    }
}
