package com.codeagent.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainSessionCommandTest {

    @Test
    void automaticResumeIsEnabledByDefault() {
        assertTrue(Main.isAutomaticSessionResumeEnabled(null, null));
    }

    @Test
    void systemPropertyCanDisableAutomaticResume() {
        assertFalse(Main.isAutomaticSessionResumeEnabled("off", "auto"));
    }

    @Test
    void environmentCanDisableAutomaticResume() {
        assertFalse(Main.isAutomaticSessionResumeEnabled(null, "off"));
    }

    @Test
    void unknownValuesRetainSafeDefault() {
        assertTrue(Main.isAutomaticSessionResumeEnabled("unexpected", null));
    }
}
