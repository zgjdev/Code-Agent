package com.codeagent.runtime.execution;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionExecutionContextRegistryTest {

    @Test
    void lazyLoadsOnceAndAllowsOnlyOneWritableLease() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        try (SessionExecutionContextRegistry<TestContext> registry =
                     new SessionExecutionContextRegistry<>(sessionId -> {
                         loads.incrementAndGet();
                         return new TestContext(sessionId);
                     }, ignored -> false, ignored -> false)) {
            try (var first = registry.acquire("session-a")) {
                assertEquals("session-a", first.context().sessionId);
                assertThrows(IllegalStateException.class, () -> registry.acquire("session-a"));
            }
            try (var second = registry.acquire("session-a")) {
                assertEquals("session-a", second.context().sessionId);
            }
            assertEquals(1, loads.get());
        }
    }

    @Test
    void sharedRuntimeAllowsOnlyOneWritableLeaseAcrossSessions() throws Exception {
        try (SessionExecutionContextRegistry<TestContext> registry =
                     new SessionExecutionContextRegistry<>(
                             TestContext::new, ignored -> false, ignored -> false)) {
            try (var first = registry.acquire("session-a")) {
                IllegalStateException failure = assertThrows(
                        IllegalStateException.class,
                        () -> registry.acquire("session-b"));
                assertTrue(failure.getMessage().contains("session-a"));
            }

            try (var second = registry.acquire("session-b")) {
                assertEquals("session-b", second.context().sessionId);
            }
        }
    }

    @Test
    void adoptsAlreadyOpenStartupContextWithoutLoadingSecondHandle() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        TestContext startup = new TestContext("session-startup");
        try (SessionExecutionContextRegistry<TestContext> registry =
                     new SessionExecutionContextRegistry<>(sessionId -> {
                         loads.incrementAndGet();
                         return new TestContext(sessionId);
                     }, ignored -> false, ignored -> false)) {
            registry.adopt("session-startup", startup);

            try (var lease = registry.acquire("session-startup")) {
                assertSame(startup, lease.context());
            }
            assertEquals(0, loads.get());
            assertThrows(IllegalStateException.class,
                    () -> registry.adopt("session-startup", new TestContext("duplicate")));
        }
    }

    @Test
    void evictionRefusesCurrentBusyOrPendingSession() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        SessionExecutionContextRegistry<TestContext> registry =
                new SessionExecutionContextRegistry<>(
                        id -> new TestContext(id, closed),
                        id -> id.equals("busy"),
                        id -> id.equals("pending"));
        try {
            registry.acquire("current").close();
            registry.acquire("busy").close();
            registry.acquire("pending").close();
            registry.acquire("idle").close();

            assertFalse(registry.evictIfIdle("current", "current"));
            assertFalse(registry.evictIfIdle("busy", "current"));
            assertFalse(registry.evictIfIdle("pending", "current"));
            assertTrue(registry.evictIfIdle("idle", "current"));
            assertEquals(1, closed.get());
        } finally {
            registry.close();
        }
    }

    private static final class TestContext implements AutoCloseable {
        private final String sessionId;
        private final AtomicInteger closed;

        private TestContext(String sessionId) {
            this(sessionId, new AtomicInteger());
        }

        private TestContext(String sessionId, AtomicInteger closed) {
            this.sessionId = sessionId;
            this.closed = closed;
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }
}
