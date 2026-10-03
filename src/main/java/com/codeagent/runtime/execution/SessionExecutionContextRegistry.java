package com.codeagent.runtime.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public final class SessionExecutionContextRegistry<T extends AutoCloseable> implements AutoCloseable {
    private final ContextFactory<T> factory;
    private final Predicate<String> hasNonTerminalExecution;
    private final Predicate<String> hasPendingInteraction;
    private final Map<String, Entry<T>> contexts = new LinkedHashMap<>();
    private String leasedSessionId;
    private boolean closed;

    public SessionExecutionContextRegistry(
            ContextFactory<T> factory,
            Predicate<String> hasNonTerminalExecution,
            Predicate<String> hasPendingInteraction) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.hasNonTerminalExecution = Objects.requireNonNull(
                hasNonTerminalExecution, "hasNonTerminalExecution");
        this.hasPendingInteraction = Objects.requireNonNull(
                hasPendingInteraction, "hasPendingInteraction");
    }

    public synchronized void adopt(String sessionId, T context) {
        requireSessionId(sessionId);
        Objects.requireNonNull(context, "context");
        if (closed) {
            throw new IllegalStateException("Session context registry is closed");
        }
        if (contexts.putIfAbsent(sessionId, new Entry<>(context)) != null) {
            throw new IllegalStateException("Session context is already loaded: " + sessionId);
        }
    }

    public synchronized Lease<T> acquire(String sessionId) throws Exception {
        requireSessionId(sessionId);
        if (closed) {
            throw new IllegalStateException("Session context registry is closed");
        }
        if (leasedSessionId != null) {
            throw new IllegalStateException(
                    "Shared runtime already has a writable execution lease: " + leasedSessionId);
        }
        Entry<T> entry = contexts.get(sessionId);
        if (entry == null) {
            entry = new Entry<>(factory.load(sessionId));
            contexts.put(sessionId, entry);
        }
        if (entry.leased) {
            throw new IllegalStateException("Session already has a writable execution lease: " + sessionId);
        }
        entry.leased = true;
        leasedSessionId = sessionId;
        return new Lease<>(this, sessionId, entry.context);
    }

    public synchronized boolean evictIfIdle(String sessionId, String currentSessionId) throws Exception {
        Entry<T> entry = contexts.get(sessionId);
        if (entry == null) {
            return false;
        }
        if (entry.leased
                || sessionId.equals(currentSessionId)
                || hasNonTerminalExecution.test(sessionId)
                || hasPendingInteraction.test(sessionId)) {
            return false;
        }
        contexts.remove(sessionId);
        entry.context.close();
        return true;
    }

    public synchronized boolean isLoaded(String sessionId) {
        return contexts.containsKey(sessionId);
    }

    private synchronized void release(String sessionId, T context) {
        Entry<T> entry = contexts.get(sessionId);
        if (entry == null || entry.context != context || !entry.leased) {
            throw new IllegalStateException("Invalid or duplicate session execution lease release: " + sessionId);
        }
        entry.leased = false;
        leasedSessionId = null;
    }

    @Override
    public synchronized void close() {
        closed = true;
        IllegalStateException failure = null;
        for (Map.Entry<String, Entry<T>> item : contexts.entrySet()) {
            if (item.getValue().leased) {
                if (failure == null) {
                    failure = new IllegalStateException(
                            "Cannot close leased session context: " + item.getKey());
                }
                continue;
            }
            try {
                item.getValue().context.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("Failed to close session context", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        contexts.entrySet().removeIf(entry -> !entry.getValue().leased);
        if (failure != null) {
            throw failure;
        }
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }

    @FunctionalInterface
    public interface ContextFactory<T> {
        T load(String sessionId) throws Exception;
    }

    public static final class Lease<T extends AutoCloseable> implements AutoCloseable {
        private final SessionExecutionContextRegistry<T> owner;
        private final String sessionId;
        private final T context;
        private boolean closed;

        private Lease(SessionExecutionContextRegistry<T> owner, String sessionId, T context) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.context = context;
        }

        public T context() {
            if (closed) {
                throw new IllegalStateException("Session execution lease is closed");
            }
            return context;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            owner.release(sessionId, context);
            closed = true;
        }
    }

    private static final class Entry<T> {
        private final T context;
        private boolean leased;

        private Entry(T context) {
            this.context = Objects.requireNonNull(context, "context");
        }
    }
}
