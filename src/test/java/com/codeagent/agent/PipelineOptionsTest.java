package com.codeagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineOptionsTest {

    @Test
    void planPresetKeepsTheHumanGateAndSkipsAutoReview() {
        assertTrue(PipelineOptions.PLAN_PRESET.humanPlanGate());
        assertFalse(PipelineOptions.PLAN_PRESET.stepReview());
    }

    @Test
    void teamPresetSkipsTheHumanGateAndEnablesAutoReview() {
        assertFalse(PipelineOptions.TEAM_PRESET.humanPlanGate());
        assertTrue(PipelineOptions.TEAM_PRESET.stepReview());
    }

    @Test
    void fullPresetEnablesBothInSeries() {
        assertTrue(PipelineOptions.FULL_PRESET.humanPlanGate());
        assertTrue(PipelineOptions.FULL_PRESET.stepReview());
    }
}
