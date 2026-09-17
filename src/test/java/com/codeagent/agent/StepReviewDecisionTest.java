package com.codeagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepReviewDecisionTest {

    @Test
    void approveFactoryCarriesNoFeedback() {
        StepReviewDecision decision = StepReviewDecision.approve();

        assertTrue(decision.approved());
        assertEquals("", decision.feedback());
    }

    @Test
    void rejectFactoryKeepsTheReviewerFeedback() {
        StepReviewDecision decision = StepReviewDecision.reject("缺少边界条件");

        assertFalse(decision.approved());
        assertEquals("缺少边界条件", decision.feedback());
    }

    @Test
    void rejectFactoryToleratesMissingFeedback() {
        StepReviewDecision decision = StepReviewDecision.reject(null);

        assertFalse(decision.approved());
        assertEquals("", decision.feedback());
    }
}
