package com.codeagent.runtime;

public final class CancellationContext {
    private static final InheritableThreadLocal<CancellationToken> LOCAL = new InheritableThreadLocal<>();

    private CancellationContext() {
    }

    public static CancellationToken startRun() {
        CancellationToken token = new CancellationToken();
        LOCAL.set(token);
        return token;
    }

    public static CancellationToken current() {
        return LOCAL.get();
    }

    public static boolean isCancelled() {
        CancellationToken token = current();
        return token != null && token.isCancelled();
    }

    public static void clear(CancellationToken token) {
        if (LOCAL.get() == token) {
            LOCAL.remove();
        }
    }

    public static Scope install(CancellationToken token) {
        if (token == null) {
            throw new IllegalArgumentException("token must not be null");
        }
        CancellationToken previous = LOCAL.get();
        LOCAL.set(token);
        return new Scope(previous, token);
    }

    public static final class Scope implements AutoCloseable {
        private final CancellationToken previous;
        private final CancellationToken installed;
        private boolean closed;

        private Scope(CancellationToken previous, CancellationToken installed) {
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
                throw new IllegalStateException("Cancellation scope closed out of order");
            }
            if (previous == null) {
                LOCAL.remove();
            } else {
                LOCAL.set(previous);
            }
        }
    }
}
