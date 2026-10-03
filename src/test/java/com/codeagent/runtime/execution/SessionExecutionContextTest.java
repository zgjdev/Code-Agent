package com.codeagent.runtime.execution;

import com.codeagent.agent.Agent;
import com.codeagent.history.SessionStore;
import com.codeagent.llm.GLMClient;
import com.codeagent.skill.SkillContextBuffer;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SessionExecutionContextTest {

    @TempDir
    Path tempDir;

    @Test
    void ownsAgentHandleAndReleasesWritableLockWithoutClosingSession() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(workspace.toString());

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history"))) {
            SessionStore.SessionHandle handle = sessions.create(new SessionStore.SessionCreateRequest(
                    workspace, "glm", "test", null, "react", "agent"));
            Agent agent = new Agent(new GLMClient("test-key"), tools);
            agent.attachSession(handle);
            SkillContextBuffer skillBuffer = new SkillContextBuffer();

            SessionExecutionContext context =
                    new SessionExecutionContext(agent, handle, skillBuffer);

            assertEquals(handle.sessionId(), context.sessionId());
            assertEquals(agent, context.agent());
            context.activateSharedBindings();
            context.close();

            assertFalse(sessions.list(workspace, 10).stream()
                    .filter(summary -> summary.sessionId().equals(handle.sessionId()))
                    .findFirst().orElseThrow().closed());
            assertDoesNotThrow(() -> {
                try (SessionStore.SessionHandle ignored =
                             sessions.resumeWritable(handle.sessionId(), workspace)) {
                    // Closing a runtime context releases only the writable handle lock.
                }
            });
        }
    }
}
