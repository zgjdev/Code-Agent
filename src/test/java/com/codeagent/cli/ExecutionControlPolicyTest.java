package com.codeagent.cli;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionControlPolicyTest {
    @Test void resumeBrowsingIsAllowedWhileBusyAndAttachUsesControllerGate() {
        assertTrue(ExecutionControlPolicy.allowedWhileBusy(CliCommandParser.CommandType.RESUME_SESSION));
        assertEquals(ExecutionControlPolicy.Category.READ_ONLY,
                ExecutionControlPolicy.classify(CliCommandParser.CommandType.RESUME_SESSION));
    }

    @Test
    void pendingInteractionBlocksNewTasksButAllowsControl() {
        assertFalse(ExecutionControlPolicy.allowedWhileInteractionPending(CliCommandParser.CommandType.NONE));
        assertFalse(ExecutionControlPolicy.allowedWhileInteractionPending(CliCommandParser.CommandType.SWITCH_PLAN));
        assertFalse(ExecutionControlPolicy.allowedWhileInteractionPending(CliCommandParser.CommandType.SWITCH_REACT));
        assertTrue(ExecutionControlPolicy.allowedWhileInteractionPending(CliCommandParser.CommandType.TASK));
        assertTrue(ExecutionControlPolicy.allowedWhileInteractionPending(CliCommandParser.CommandType.CANCEL));
        assertTrue(ExecutionControlPolicy.allowedWhileInteractionPending(CliCommandParser.CommandType.SESSIONS));
    }

    @Test
    void everyCommandTypeHasAnExplicitBusyClassification() {
        assertEquals(CliCommandParser.CommandType.values().length,
                Arrays.stream(CliCommandParser.CommandType.values())
                        .map(ExecutionControlPolicy::classify)
                        .filter(java.util.Objects::nonNull)
                        .count());
    }

    @Test
    void allowsQueueAndControlButRejectsMutableSessionStateWhileBusy() {
        assertTrue(ExecutionControlPolicy.allowedWhileBusy(CliCommandParser.CommandType.NONE));
        assertTrue(ExecutionControlPolicy.allowedWhileBusy(CliCommandParser.CommandType.SWITCH_PLAN));
        assertTrue(ExecutionControlPolicy.allowedWhileBusy(CliCommandParser.CommandType.TASK));
        assertTrue(ExecutionControlPolicy.allowedWhileBusy(CliCommandParser.CommandType.CANCEL));
        assertFalse(ExecutionControlPolicy.allowedWhileBusy(CliCommandParser.CommandType.CLEAR));
        assertFalse(ExecutionControlPolicy.allowedWhileBusy(CliCommandParser.CommandType.SWITCH_MODEL));
        assertFalse(ExecutionControlPolicy.allowedWhileBusy(CliCommandParser.CommandType.PLAN_RESUME));
        assertFalse(ExecutionControlPolicy.allowedWhileBusy(CliCommandParser.CommandType.UNKNOWN_COMMAND));
    }
}
