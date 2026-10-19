package com.codeagent.runtime.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionRecoveryGateTest {
    @TempDir Path temporary;

    @Test void createsSchemaFourForDurableRecoveryEligibility() throws Exception {
        Path database = temporary.resolve("execution.db");
        try (var store = new RuntimeExecutionStore(database);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT MAX(version) FROM runtime_schema_migrations")) {
            assertTrue(rows.next());
            assertEquals(4, rows.getInt(1), "Recovery eligibility and semantics must be understood by the database schema");
        }
    }

    @Test void freezesAllOldInputsButNewInputBypassesHeldFifoHead() throws Exception {
        try (var store = new RuntimeExecutionStore(temporary.resolve("execution.db"))) {
            var old = store.enqueue(temporary, "session", "old task", null);
            var running = store.claimNext(temporary, "old-owner").orElseThrow();
            var heldSecond = store.enqueue(temporary, "session", "old queued", null);
            var identity = new WorkspaceIdentityResolver().resolve(temporary);
            try (var owner = WorkspaceOwnerLease.acquire(temporary.resolve("locks"), identity, "new-owner")) {
                freeze(store, owner);
                assertTrue(store.claimNext(temporary, "new-owner").isEmpty(), "Old work must await new input");
                assertEquals(ExecutionStatus.ENQUEUED, store.find(old.id()).orElseThrow().status());
                assertNull(store.find(old.id()).orElseThrow().ownerId());
                assertTrue(store.hasNonTerminal("session"));
                assertThrows(IllegalStateException.class, () -> store.complete(running.claim(), ExecutionOutcome.SUCCEEDED, "late", null));
                var fresh = store.enqueue(temporary, "session", "where were we?", null);
                assertEquals(fresh.id(), store.nextQueuedExecution(identity.key()).orElseThrow().id());
                assertEquals(fresh.id(), store.claimNext(temporary, "new-owner").orElseThrow().id());
                store.enqueue(temporary, "another-session", "another new input", null);
                assertTrue(store.nextQueuedExecution(identity.key()).isEmpty(), "Read-only candidate selection must agree with a running workspace claim guard");
                assertTrue(store.claimNext(temporary,"new-owner").isEmpty());
                assertEquals(ExecutionStatus.ENQUEUED, store.find(heldSecond.id()).orElseThrow().status());
            }
        }
    }

    @Test void freezingPreservesTerminalCancellationAndOtherWorkspace() throws Exception {
        var a = java.nio.file.Files.createDirectory(temporary.resolve("a"));
        var b = java.nio.file.Files.createDirectory(temporary.resolve("b"));
        try (var store = new RuntimeExecutionStore(temporary.resolve("execution.db"))) {
            var canceled = store.enqueue(a, "a-session", "cancel", null);
            store.requestCancel(canceled.id());
            var running = store.enqueue(a, "a-session", "running cancel", null);
            running = store.claimNext(a, "previous").orElseThrow();
            store.requestCancel(running.id());
            var other = store.enqueue(b, "b-session", "other", null);
            try (var owner = WorkspaceOwnerLease.acquire(temporary.resolve("locks"), new WorkspaceIdentityResolver().resolve(a), "new")) {
                freeze(store, owner);
                assertEquals(ExecutionStatus.CANCELED, store.find(running.id()).orElseThrow().status());
                assertEquals(ExecutionStatus.CANCELED, store.find(canceled.id()).orElseThrow().status());
                assertEquals(other, store.find(other.id()).orElseThrow());
            }
        }
    }

    @Test void continuationRequiresCompletedControlAndPreservesTargetIdentityAndPolicy() throws Exception {
        try (var store = new RuntimeExecutionStore(temporary.resolve("execution.db"));
             var owner = WorkspaceOwnerLease.acquire(temporary.resolve("locks"), new WorkspaceIdentityResolver().resolve(temporary), "owner")) {
            var target = store.enqueue(temporary, "session", "original", com.codeagent.agent.ExecutionMode.PLAN);
            var original = store.claimNext(temporary, "old").orElseThrow();
            store.commitRoutingDecision(original.claim(), com.codeagent.agent.ExecutionMode.PLAN, com.codeagent.agent.RoutingSource.EXPLICIT);
            freeze(store, owner);
            int generation = store.find(target.id()).orElseThrow().recoveryGeneration();
            assertFalse(store.hasActiveOrReadyExecution("session"));
            var request = store.enqueue(temporary, "session", "continue without tests", null);
            request = store.claimCandidate(owner, request.id()).orElseThrow();
            prepare(store, request.claim(), target.id(), generation);
            assertEquals("NOT_READY", release(store, owner, request.id(), target.id(), generation));
            assertTrue(store.find(target.id()).orElseThrow().awaitingInput());
            store.complete(request.claim(), ExecutionOutcome.SUCCEEDED, "accepted", null);
            assertEquals("RELEASED", release(store, owner, request.id(), target.id(), generation));
            assertEquals("ALREADY_RELEASED", release(store, owner, request.id(), target.id(), generation));
            var released = store.find(target.id()).orElseThrow();
            assertEquals("original", released.submittedInput());
            assertEquals("continue without tests", released.currentPolicyInput());
            assertEquals(request.id(), released.continuationRequestId());
            assertEquals(com.codeagent.agent.ExecutionMode.PLAN, released.selectedMode());
            var resumed = store.claimCandidate(owner, target.id()).orElseThrow();
            assertEquals(original.attempt()+1, resumed.attempt());
            defer(store, resumed.claim(), "preparation failed");
            var deferred = store.find(target.id()).orElseThrow();
            assertTrue(deferred.awaitingInput());
            assertEquals(generation+1, deferred.recoveryGeneration());
            assertNull(deferred.currentPolicyInput());
            assertEquals("STALE", release(store, owner, request.id(), target.id(), generation));
        }
    }

    @Test void continuationRejectsCrossSessionAndStaleGeneration() throws Exception {
        try (var store = new RuntimeExecutionStore(temporary.resolve("execution.db"));
             var owner = WorkspaceOwnerLease.acquire(temporary.resolve("locks"), new WorkspaceIdentityResolver().resolve(temporary), "owner")) {
            var target = store.enqueue(temporary, "session", "old", null);
            freeze(store, owner);
            int generation = store.find(target.id()).orElseThrow().recoveryGeneration();
            var unrelated = store.enqueue(temporary, "other-session", "continue", null);
            unrelated = store.claimCandidate(owner, unrelated.id()).orElseThrow();
            var claim = unrelated.claim();
            assertThrows(IllegalStateException.class, () -> prepare(store, claim, target.id(), generation));
            store.complete(claim, ExecutionOutcome.SUCCEEDED, "no", null);
            var request = store.enqueue(temporary, "session", "continue", null);
            request = store.claimCandidate(owner, request.id()).orElseThrow();
            var correctClaim = request.claim();
            assertThrows(IllegalStateException.class, () -> prepare(store, correctClaim, target.id(), generation+1));
            prepare(store, correctClaim, target.id(), generation);
            store.complete(correctClaim, ExecutionOutcome.SUCCEEDED, "yes", null);
            freeze(store, owner);
            assertEquals("STALE", release(store, owner, request.id(), target.id(), generation));
        }
    }

    @Test void endReconciliationRequiresCurrentOwnerSnapshotAndCompleteEnvelope() throws Exception {
        Path root = java.nio.file.Files.createDirectory(temporary.resolve("workspace"));
        Path otherRoot = java.nio.file.Files.createDirectory(temporary.resolve("other"));
        try (var store = new RuntimeExecutionStore(temporary.resolve("execution.db"));
             var owner = WorkspaceOwnerLease.acquire(temporary.resolve("locks"), new WorkspaceIdentityResolver().resolve(root), "new");
             var otherOwner = WorkspaceOwnerLease.acquire(temporary.resolve("locks"), new WorkspaceIdentityResolver().resolve(otherRoot), "other")) {
            store.enqueue(root, "session", "old", null);
            var running = store.claimNext(root, "previous").orElseThrow();
            var envelope = new com.codeagent.history.SessionProjection.ExecutionEnvelope(running.id(), running.ordinal(), null,
                    1, null, null, "react", "succeeded", "completed", 2L);
            assertThrows(IllegalStateException.class, () -> store.completeReconciliation(otherOwner, running, envelope));
            store.requestCancel(running.id()); // Same owner/attempt, but the observed state is no longer current.
            assertThrows(IllegalStateException.class, () -> store.completeReconciliation(owner, running, envelope));
            var current = store.find(running.id()).orElseThrow();
            var wrongIdentity = new com.codeagent.history.SessionProjection.ExecutionEnvelope("another-execution", current.ordinal(), null,
                    1, null, null, "react", "succeeded", "completed", 2L);
            assertThrows(IllegalArgumentException.class, () -> store.completeReconciliation(owner, current, wrongIdentity));
            var incomplete = new com.codeagent.history.SessionProjection.ExecutionEnvelope(current.id(), current.ordinal(), null,
                    1, null, null, "react", null, "completed", 2L);
            assertThrows(IllegalArgumentException.class, () -> store.completeReconciliation(owner, current, incomplete));
            assertEquals(current, store.find(running.id()).orElseThrow());
            var reconciled = store.completeReconciliation(owner, current, envelope);
            assertEquals(ExecutionStatus.COMPLETED, reconciled.status());
            assertEquals(com.codeagent.agent.ExecutionMode.REACT, reconciled.selectedMode(), "Missing routing can be restored from validated END");
            assertEquals(com.codeagent.agent.RoutingSource.AUTO_FALLBACK, reconciled.routingSource());
            assertEquals(running.attempt() + 1, reconciled.attempt());
            assertThrows(IllegalStateException.class, () -> store.complete(running.claim(), ExecutionOutcome.SUCCEEDED, "late", null));
            otherOwner.close();
            assertThrows(java.sql.SQLException.class, () -> store.completeReconciliation(otherOwner, current, envelope));
        }
    }

    private static RuntimeExecution prepare(RuntimeExecutionStore store, ExecutionClaim claim, String target, int generation) throws Exception {
        return store.prepareContinuation(claim,target,generation);
    }
    private static String release(RuntimeExecutionStore store, WorkspaceOwnerLease owner, String request, String target, int generation) throws Exception {
        return store.releaseContinuation(owner,request,target,generation).name();
    }
    private static void defer(RuntimeExecutionStore store, ExecutionClaim claim, String reason) throws Exception {
        store.deferForUser(claim,reason);
    }
    private static void freeze(RuntimeExecutionStore store, WorkspaceOwnerLease owner) throws Exception {
        store.freezeForUser(owner);
    }
}
