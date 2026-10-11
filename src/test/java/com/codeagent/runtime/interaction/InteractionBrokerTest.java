package com.codeagent.runtime.interaction;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class InteractionBrokerTest {

    @Test
    void executionIdentityIsVerifiedAtomically() {
        try (InteractionBroker broker = new InteractionBroker()) {
            var result = broker.request(new InteractionRequest("i", "e", "s", InteractionKind.HITL, Set.of("approve"), ""));
            assertFalse(broker.respond("wrong", "i", "approve", ""));
            assertFalse(result.isDone());
            assertTrue(broker.respond("e", "i", "approve", ""));
        }
    }

    @Test
    void allowsOnlyOnePendingInteractionAndRoutesByIdentity() throws Exception {
        try (InteractionBroker broker = new InteractionBroker()) {
            InteractionRequest first = new InteractionRequest(
                    "interaction-1", "exec-1", "session-1", InteractionKind.PLAN_REVIEW,
                    Set.of("execute", "cancel", "supplement"), "review plan");
            var response = broker.request(first);

            assertThrows(IllegalStateException.class, () -> broker.request(new InteractionRequest(
                    "interaction-2", "exec-2", "session-2", InteractionKind.HITL,
                    Set.of("approve", "reject"), "approve tool")));
            assertFalse(broker.respond("wrong-id", "execute", ""));
            assertTrue(broker.respond("interaction-1", "execute", ""));
            assertEquals("execute", response.get().action());
            assertTrue(broker.pending().isEmpty());
        }
    }

    @Test
    void invalidActionKeepsRequestPendingAndLateResponseIsRejected() throws Exception {
        try (InteractionBroker broker = new InteractionBroker()) {
            var response = broker.request(new InteractionRequest(
                    "interaction-1", "exec-1", "session-1", InteractionKind.HITL,
                    Set.of("approve", "reject"), "approve tool"));

            assertThrows(IllegalArgumentException.class,
                    () -> broker.respond("interaction-1", "yes", "free text"));
            assertTrue(broker.pending().isPresent());
            assertTrue(broker.respond("interaction-1", "reject", "no"));
            assertEquals("reject", response.get().action());
            assertFalse(broker.respond("interaction-1", "approve", "late"));
        }
    }

    @Test
    void terminalExecutionClearsPendingRequestExceptionally() {
        try (InteractionBroker broker = new InteractionBroker()) {
            var response = broker.request(new InteractionRequest(
                    "interaction-1", "exec-1", "session-1", InteractionKind.PLAN_REVIEW,
                    Set.of("execute", "cancel"), "review"));

            assertTrue(broker.cancelExecution("exec-1", "execution terminal"));
            assertThrows(ExecutionException.class, response::get);
            assertTrue(broker.pending().isEmpty());
        }
    }
}
