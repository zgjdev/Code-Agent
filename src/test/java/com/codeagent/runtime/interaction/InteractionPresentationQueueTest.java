package com.codeagent.runtime.interaction;

import com.codeagent.runtime.execution.WorkspaceKey;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class InteractionPresentationQueueTest {
    @Test void cancellationAndLateResponsesCannotChangeAnotherWorkspace() {
        var a = new WorkspaceKey("a"); var b = new WorkspaceKey("b");
        try (var ba = new InteractionBroker(); var bb = new InteractionBroker()) {
            var queue = new InteractionPresentationQueue();
            var ra = new InteractionRequest("ia", "ea", "sa", InteractionKind.HITL, Set.of("approve"), "");
            var rb = new InteractionRequest("ib", "eb", "sb", InteractionKind.HITL, Set.of("approve"), "");
            var fa = ba.request(ra); var fb = bb.request(rb);
            queue.submit(a, ra, ba); queue.submit(b, rb, bb);
            assertEquals(ra, queue.focused().orElseThrow().request());
            assertFalse(queue.respond(b, "eb", "ib", "approve", ""));
            queue.cancelExecution(a, "ea", "canceled");
            assertTrue(fa.isCompletedExceptionally()); assertFalse(fb.isDone());
            assertFalse(queue.respond(a, "ea", "ia", "approve", ""));
            assertTrue(queue.respond(b, "eb", "ib", "approve", ""));
            assertTrue(fb.isDone()); assertTrue(queue.focused().isEmpty());
        }
    }
}
