package com.codeagent.context;

import com.codeagent.llm.LlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SummaryCallBudgetTest {
    @Test void reservesRepairBeforeStartingAndReleasesUnusedSlot() {
        var budget = new SummaryCallBudget(2);
        try (var reservation = budget.reserveOperation()) {
            assertNotNull(reservation);
            assertEquals(0, budget.remainingCalls());
            assertNull(budget.reserveOperation());
            reservation.beforeCall();
            reservation.recordUsage(new LlmClient.ChatResponse("assistant", "ok", null, null, 20, 5, 3));
        }
        assertEquals(1, budget.completedCalls());
        assertEquals(1, budget.remainingCalls());
        assertNull(budget.reserveOperation(), "one free call cannot reserve a possible repair");
        assertEquals(new SummaryCallBudget.Usage(20, 5, 3), budget.usage());
    }

    @Test void failedAndRepairCallsConsumeBudgetAndCannotBypassCap() {
        var budget = new SummaryCallBudget(2);
        try (var reservation = budget.reserveOperation()) {
            reservation.beforeCall(); // Provider failure with no trustworthy usage still consumed a call.
            reservation.recordUsage(null);
            reservation.beforeCall();
            reservation.recordUsage(new LlmClient.ChatResponse("assistant", "invalid", null, null, 30, 6, 0));
            assertThrows(IllegalStateException.class, reservation::beforeCall);
        }
        assertEquals(2, budget.completedCalls());
        assertEquals(0, budget.remainingCalls());
        assertNull(budget.reserveOperation());
        assertEquals(30, budget.usage().inputTokens());
    }

    @Test void closeIsIdempotentAndUsageCannotBeCountedTwice() {
        var budget = new SummaryCallBudget(16);
        var reservation = budget.reserveOperation();
        reservation.beforeCall();
        reservation.recordUsage(null);
        assertThrows(IllegalStateException.class, () -> reservation.recordUsage(null));
        reservation.close();
        reservation.close();
        assertEquals(15, budget.remainingCalls());
        assertThrows(IllegalStateException.class, reservation::beforeCall);
    }

    @Test void parallelReservationsCannotOverbookAndUnstartedOperationReleasesBoth() {
        var budget = new SummaryCallBudget(4);
        var first = budget.reserveOperation();
        var second = budget.reserveOperation();
        assertNull(budget.reserveOperation());
        first.close();
        assertEquals(2, budget.remainingCalls());
        second.close();
        assertEquals(4, budget.remainingCalls());
        assertEquals(0, budget.completedCalls());
    }
}
