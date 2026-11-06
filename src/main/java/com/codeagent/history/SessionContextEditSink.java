package com.codeagent.history;

import com.codeagent.context.*;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

/** Durable adapter; runtime ownership and protection remain with the calling execution. */
public final class SessionContextEditSink implements ContextEditSink {
    private final SessionStore.SessionHandle session;
    private final Supplier<ProtectionSet> protection;
    public SessionContextEditSink(SessionStore.SessionHandle session, Supplier<ProtectionSet> protection) {
        this.session = Objects.requireNonNull(session);
        this.protection = Objects.requireNonNull(protection);
    }
    @Override public CommitResult commit(ContextEditBatch batch) throws IOException {
        return session.commitContextEdit(batch, protection);
    }
}
