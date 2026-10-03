package com.codeagent.runtime.interaction;

/** Execution identity propagated to worker code that may request CLI interaction. */
public final class ExecutionInteractionContext {
    private static final InheritableThreadLocal<Identity> LOCAL = new InheritableThreadLocal<>();

    private ExecutionInteractionContext() {
    }

    public static Identity current() {
        return LOCAL.get();
    }

    public static Scope install(String executionId, String sessionId) {
        Identity installed = new Identity(executionId, sessionId);
        Identity previous = LOCAL.get();
        LOCAL.set(installed);
        return new Scope(previous, installed);
    }

    public record Identity(String executionId, String sessionId) {
        public Identity {
            if (executionId == null || executionId.isBlank()) {
                throw new IllegalArgumentException("executionId must not be blank");
            }
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId must not be blank");
            }
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Identity previous;
        private final Identity installed;
        private boolean closed;

        private Scope(Identity previous, Identity installed) {
            this.previous = previous;
            this.installed = installed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (LOCAL.get() != installed) {
                throw new IllegalStateException("Interaction scope closed out of order");
            }
            if (previous == null) {
                LOCAL.remove();
            } else {
                LOCAL.set(previous);
            }
        }
    }
}
