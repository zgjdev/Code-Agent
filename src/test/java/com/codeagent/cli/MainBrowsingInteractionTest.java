package com.codeagent.cli;

import com.codeagent.runtime.interaction.*;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MainBrowsingInteractionTest {
    @Test void newlyArrivingApprovalTakesPrecedenceOverOpenBrowser() throws Exception {
        try (var broker = new InteractionBroker()) {
            var canceled = new AtomicInteger(); var browsed = new AtomicInteger();
            var answer = broker.request(new InteractionRequest("approval", "e", "s", InteractionKind.HITL,
                    Set.of("approve", "reject"), "approve?"));
            assertEquals(InteractionInputRouter.Result.INVALID, Main.routePendingInput(broker, () -> true,
                    canceled::incrementAndGet, text -> browsed.incrementAndGet(), "not an option"));
            assertFalse(answer.isDone());
            assertEquals(InteractionInputRouter.Result.CONSUMED, Main.routePendingInput(broker, () -> true,
                    canceled::incrementAndGet, text -> browsed.incrementAndGet(), "y"));
            assertEquals("approve", answer.get().action());
            assertEquals(0, browsed.get()); assertEquals(2, canceled.get());
        }
    }
    @Test void planSupplementReachesCurrentReviewWhileBrowsing() throws Exception {
        try (var broker = new InteractionBroker()) {
            var answer = broker.request(new InteractionRequest("review", "e", "s", InteractionKind.PLAN_REVIEW,
                    Set.of("execute", "supplement", "cancel"), "review"));
            assertEquals(InteractionInputRouter.Result.CONSUMED, Main.routePendingInput(broker, () -> true,
                    () -> {}, text -> fail("browser consumed review"), "only inspect files"));
            assertEquals("supplement", answer.get().action());
        }
    }
}
