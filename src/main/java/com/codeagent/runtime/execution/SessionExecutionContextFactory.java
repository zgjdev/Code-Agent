package com.codeagent.runtime.execution;

import com.codeagent.agent.Agent;
import com.codeagent.history.SessionStore;
import com.codeagent.skill.SkillContextBuffer;

import java.nio.file.Path;
import java.util.Objects;

/** Lazily restores one durable Session and builds its isolated in-memory Agent context. */
public final class SessionExecutionContextFactory {
    private final SessionStore sessionStore;
    private final Path workspace;
    private final AgentFactory agentFactory;

    public SessionExecutionContextFactory(
            SessionStore sessionStore,
            Path workspace,
            AgentFactory agentFactory) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
    }

    public SessionExecutionContext load(String sessionId) throws Exception {
        SessionStore.SessionHandle handle = sessionStore.resumeWritable(sessionId, workspace);
        try {
            SkillContextBuffer skillContextBuffer = new SkillContextBuffer();
            Agent agent = Objects.requireNonNull(
                    agentFactory.create(skillContextBuffer), "agentFactory result");
            agent.setSkillContextBuffer(skillContextBuffer);
            agent.attachSession(handle);
            return new SessionExecutionContext(agent, handle, skillContextBuffer);
        } catch (Exception | Error failure) {
            try {
                handle.close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @FunctionalInterface
    public interface AgentFactory {
        Agent create(SkillContextBuffer skillContextBuffer) throws Exception;
    }
}
