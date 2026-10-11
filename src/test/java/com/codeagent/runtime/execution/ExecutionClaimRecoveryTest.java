package com.codeagent.runtime.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionClaimRecoveryTest {
    @Test
    void previousAttemptCannotMutateExecutionAfterRecoveryAndReclaim(@TempDir Path tempDir) throws Exception {
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("runtime.db"))) {
            store.enqueue(tempDir, "session-a", "input", null);
            RuntimeExecution firstAttempt = store.claimNext(tempDir, "owner-old").orElseThrow();
            ExecutionClaim staleClaim = firstAttempt.claim();
            store.interruptForRecovery(staleClaim, "crash");

            RuntimeExecution secondAttempt = store.claimNext(tempDir, "owner-new").orElseThrow();
            assertEquals(firstAttempt.attempt() + 1, secondAttempt.attempt());
            assertEquals("owner-new", secondAttempt.ownerId());

            assertThrows(IllegalStateException.class, () -> store.commitResolvedInput(staleClaim, "stale input"));
            assertThrows(IllegalStateException.class, () -> store.commitRoutingDecision(staleClaim,
                    com.codeagent.agent.ExecutionMode.REACT, com.codeagent.agent.RoutingSource.EXPLICIT));
            assertThrows(IllegalStateException.class, () -> store.complete(
                    staleClaim, ExecutionOutcome.SUCCEEDED, "stale result", null));
            assertThrows(IllegalStateException.class, () -> store.interruptForRecovery(staleClaim, "late callback"));

            RuntimeExecution current = store.find(secondAttempt.id()).orElseThrow();
            assertEquals(ExecutionStatus.RUNNING, current.status());
            assertEquals("owner-new", current.ownerId());
            assertEquals(2, current.attempt());
        }
    }
}
