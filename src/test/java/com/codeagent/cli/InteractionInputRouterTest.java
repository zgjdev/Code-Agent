package com.codeagent.cli;

import com.codeagent.runtime.interaction.InteractionBroker;
import com.codeagent.runtime.interaction.InteractionKind;
import com.codeagent.runtime.interaction.InteractionRequest;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InteractionInputRouterTest {

    @Test
    void planTextIsSupplementAndEnterExecutes() throws Exception {
        InteractionBroker broker = new InteractionBroker();
        var first = broker.request(request(InteractionKind.PLAN_REVIEW,
                Set.of("execute", "supplement", "cancel")));

        assertEquals(InteractionInputRouter.Result.CONSUMED,
                InteractionInputRouter.route(broker, "add verification"));
        assertEquals("supplement", first.get().action());
        assertEquals("add verification", first.get().text());

        var second = broker.request(request(InteractionKind.PLAN_REVIEW,
                Set.of("execute", "supplement", "cancel")));
        assertEquals(InteractionInputRouter.Result.CONSUMED,
                InteractionInputRouter.route(broker, ""));
        assertEquals("execute", second.get().action());
    }

    @Test
    void invalidHitlInputRemainsPendingAndIsNeverEnqueued() {
        InteractionBroker broker = new InteractionBroker();
        broker.request(request(InteractionKind.HITL, Set.of("approve", "reject")));

        assertEquals(InteractionInputRouter.Result.INVALID,
                InteractionInputRouter.route(broker, "anything else"));
        assertTrue(broker.pending().isPresent());
    }

    @Test
    void hitlModifyRequiresValidJson() throws Exception {
        InteractionBroker broker = new InteractionBroker();
        var response = broker.request(request(InteractionKind.HITL,
                Set.of("approve", "reject", "modify")));

        assertEquals(InteractionInputRouter.Result.INVALID,
                InteractionInputRouter.route(broker, "m nope"));
        assertEquals(InteractionInputRouter.Result.CONSUMED,
                InteractionInputRouter.route(broker, "m {\"path\":\"a\"}"));
        assertEquals("modify", response.get().action());
        assertEquals("{\"path\":\"a\"}", response.get().text());
    }

    @Test
    void returnsNoPendingWithoutConsumingInput() {
        assertEquals(InteractionInputRouter.Result.NO_PENDING,
                InteractionInputRouter.route(new InteractionBroker(), "hello"));
    }

    private static InteractionRequest request(InteractionKind kind, Set<String> actions) {
        return new InteractionRequest("interaction-1", "exec-1", "session-1",
                kind, actions, "prompt");
    }
}
