package com.codeagent.runtime.execution;

import com.codeagent.agent.Agent;
import com.codeagent.history.SessionStore;
import com.codeagent.llm.GLMClient;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class SessionExecutionContextFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void resumesWritableSessionAndAttachesFreshAgent() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        AtomicInteger agents = new AtomicInteger();
        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history"))) {
            String sessionId;
            try (SessionStore.SessionHandle created = sessions.create(
                    new SessionStore.SessionCreateRequest(
                            workspace, "glm", "test", null, "react", "agent"))) {
                sessionId = created.sessionId();
            }
            ToolRegistry tools = new ToolRegistry();
            tools.setProjectPath(workspace.toString());
            SessionExecutionContextFactory factory = new SessionExecutionContextFactory(
                    sessions, workspace, skillBuffer -> {
                        agents.incrementAndGet();
                        Agent agent = new Agent(new GLMClient("test-key"), tools);
                        agent.setSkillContextBuffer(skillBuffer);
                        return agent;
                    });

            try (SessionExecutionContext context = factory.load(sessionId)) {
                assertEquals(sessionId, context.sessionId());
                assertSame(context.session(), context.agent().getSessionHandle());
                assertEquals(1, agents.get());
            }
        }
    }

    @Test
    void releasesWritableHandleWhenAgentCreationFails() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("failed-workspace"));
        try (SessionStore sessions = SessionStore.open(tempDir.resolve("failed-history"))) {
            String sessionId;
            try (SessionStore.SessionHandle created = sessions.create(
                    new SessionStore.SessionCreateRequest(
                            workspace, "glm", "test", null, "react", "agent"))) {
                sessionId = created.sessionId();
            }
            SessionExecutionContextFactory factory = new SessionExecutionContextFactory(
                    sessions, workspace, ignored -> {
                        throw new IllegalStateException("agent creation failed");
                    });

            assertThrows(IllegalStateException.class, () -> factory.load(sessionId));
            try (SessionStore.SessionHandle resumed =
                         sessions.resumeWritable(sessionId, workspace)) {
                assertEquals(sessionId, resumed.sessionId());
            }
        }
    }
}
