package com.codeagent.runtime.execution;

import com.codeagent.agent.Agent;
import com.codeagent.history.SessionStore;
import com.codeagent.skill.SkillContextBuffer;

import java.util.Objects;

/** Complete writable runtime state owned by one durable Session. */
public final class SessionExecutionContext implements AutoCloseable {
    private final Agent agent;
    private final SessionStore.SessionHandle session;
    private final SkillContextBuffer skillContextBuffer;

    public SessionExecutionContext(
            Agent agent,
            SessionStore.SessionHandle session,
            SkillContextBuffer skillContextBuffer) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.session = Objects.requireNonNull(session, "session");
        this.skillContextBuffer = Objects.requireNonNull(skillContextBuffer, "skillContextBuffer");
        if (agent.getSessionHandle() != session) {
            throw new IllegalArgumentException("Agent must be attached to the owned SessionHandle");
        }
    }

    public String sessionId() {
        return session.sessionId();
    }

    public Agent agent() {
        return agent;
    }

    public SessionStore.SessionHandle session() {
        return session;
    }

    public SkillContextBuffer skillContextBuffer() {
        return skillContextBuffer;
    }

    public void activateSharedBindings() {
        agent.setSkillContextBuffer(skillContextBuffer);
        agent.activateSharedToolContext();
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
