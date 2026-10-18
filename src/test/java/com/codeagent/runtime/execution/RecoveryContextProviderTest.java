package com.codeagent.runtime.execution;

import com.codeagent.history.*;
import com.codeagent.plan.*;
import com.codeagent.model.recovery.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryContextProviderTest {

    @TempDir
    Path temp;

    @Test
    void canonicalAncestorPrefixAlsoParticipatesInSnapshotFingerprint() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        var identity = new WorkspaceIdentityResolver().resolve(workspace);
        try (var sessions = SessionStore.open(temp.resolve("history"));
             var parent = sessions.create(new SessionStore.SessionCreateRequest(workspace, "test", "model", null, "plan", "agent"));
             var executions = new RuntimeExecutionStore(temp.resolve("tasks.db"));
             var source = parent.createChild("plan", "task:t");
             var child = parent.createChild("plan", "task:t")) {
            var running = executions.enqueue(workspace, parent.sessionId(), "original", null);
            var claim = executions.claimNext(workspace).orElseThrow().claim();
            var plans = new PlanStateStore(temp.resolve("plans.db"));
            var plan = new ExecutionPlan("plan", "goal");
            plan.addTask(new Task("t", "description", Task.TaskType.FILE_READ)); plan.computeExecutionOrder();
            plans.savePlan(workspace, parent.sessionId(), running.id(), "policy", plan);
            var attempts = new TaskRecoveryStore(plans.dbPath());
            var original = attempts.bindAttempt(workspace, parent.sessionId(), running.id(), "plan", "t", source.sessionId(), null, claim.ownerId(), claim.attempt());
            var system = json.createObjectNode(); system.putObject("message").put("role", "system").put("content", "synthetic-origin-before");
            source.append(new SessionEventDraft(SessionEvent.Types.SYSTEM_MESSAGE, "plan", "task:t", "test", false, SessionEvent.SurfaceOperation.append(), system));
            var text = json.createObjectNode(); text.putObject("message").put("role", "assistant").put("content", "stable group");
            source.append(new SessionEventDraft(SessionEvent.Types.ASSISTANT_MESSAGE, "plan", "task:t", "test", false, SessionEvent.SurfaceOperation.append(), text));
            var group = new TaskTranscriptReader(sessions, attempts).read(original, -1).groups().get(0);
            attempts.bindAttempt(workspace, parent.sessionId(), running.id(), "plan", "t", child.sessionId(), original.attemptId(), claim.ownerId(), claim.attempt());
            var imported = json.createObjectNode();
            imported.putObject("message").put("role", "assistant").put("content", "ignored import copy");
            imported.putObject("recoveryOrigin").put("attemptId", original.attemptId()).put("childSessionId", source.sessionId())
                    .put("firstSequence", group.firstSequence()).put("lastSequence", group.lastSequence()).put("contentHash", group.contentHash());
            child.append(new SessionEventDraft(SessionEvent.Types.ASSISTANT_MESSAGE, "plan", "task:t", "recovery_import", false, SessionEvent.SurfaceOperation.append(), imported));
            var provider = new RecoveryContextProvider(executions, plans, sessions, attempts, (w, p, c) -> {});
            var boundary = new RecoveryContextProvider.ReadBoundary(identity, parent.sessionId(), claim);
            var first = provider.readRecoveryContext(boundary);
            Path log = source.sessionDirectory().resolve("events.jsonl");
            Files.writeString(log, Files.readString(log).replace("synthetic-origin-before", "synthetic-origin-after!"));
            assertFalse(provider.isCurrent(first, boundary), "every loaded child prefix, including ignored ancestor events, affects integrity");
            var second = provider.readRecoveryContext(boundary);
            assertNotEquals(first.stateFingerprint(), second.stateFingerprint());
            assertEquals(2, second.plans().get(0).tasks().get(0).sources().stream().filter(r -> "child".equals(r.store())).count());
        }
    }

    @Test
    void childContentChangeAtSameSequenceInvalidatesSnapshot() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        var identity = new WorkspaceIdentityResolver().resolve(workspace);
        try (var sessions = SessionStore.open(temp.resolve("history"));
             var parent = sessions.create(new SessionStore.SessionCreateRequest(workspace, "test", "model", null, "plan", "agent"));
             var executions = new RuntimeExecutionStore(temp.resolve("tasks.db"));
             var child = parent.createChild("plan", "task:t")) {
            var running = executions.enqueue(workspace, parent.sessionId(), "original", null);
            var claim = executions.claimNext(workspace).orElseThrow().claim();
            var plans = new PlanStateStore(temp.resolve("plans.db"));
            var plan = new ExecutionPlan("plan", "goal");
            plan.addTask(new Task("t", "description", Task.TaskType.FILE_READ));
            plan.computeExecutionOrder();
            plans.savePlan(workspace, parent.sessionId(), running.id(), "policy", plan);
            var attempts = new TaskRecoveryStore(plans.dbPath());
            attempts.bindAttempt(workspace, parent.sessionId(), running.id(), plan.getId(), "t", child.sessionId(), null, claim.ownerId(), claim.attempt());
            var payload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            payload.putObject("message").put("role", "assistant").put("content", "synthetic-before");
            child.append(new SessionEventDraft(SessionEvent.Types.ASSISTANT_MESSAGE, "plan", "task:t", "test", false, SessionEvent.SurfaceOperation.append(), payload));
            var provider = new RecoveryContextProvider(executions, plans, sessions, attempts, (w, p, c) -> {});
            var boundary = new RecoveryContextProvider.ReadBoundary(identity, parent.sessionId(), claim);
            var first = provider.readRecoveryContext(boundary);
            assertTrue(provider.isCurrent(first, boundary));
            Path log = child.sessionDirectory().resolve("events.jsonl");
            String content = Files.readString(log);
            assertTrue(content.contains("synthetic-before"));
            // Isolated corruption simulation only: preserve all event identities and sequence numbers.
            Files.writeString(log, content.replace("synthetic-before", "synthetic-after!"));
            var second = provider.readRecoveryContext(boundary);
            assertFalse(provider.isCurrent(first, boundary));
            assertNotEquals(first.stateFingerprint(), second.stateFingerprint());
            var oldRef = first.plans().get(0).tasks().get(0).sources().stream().filter(r -> "child".equals(r.store())).findFirst().orElseThrow();
            var newRef = second.plans().get(0).tasks().get(0).sources().stream().filter(r -> "child".equals(r.store())).findFirst().orElseThrow();
            assertNotNull(oldRef.contentHash());
            assertEquals(oldRef.sequence(), newRef.sequence());
            assertNotEquals(oldRef.contentHash(), newRef.contentHash());
            assertFalse(first.toString().contains("synthetic-before"));
        }
    }

    @Test
    void readsHeldPlanWithoutMutatingAndVerifiesBoundaryBeforeReading() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        var identity = new WorkspaceIdentityResolver().resolve(workspace);
        try (var sessions = SessionStore.open(temp.resolve("history"));
            var parent = sessions.create(new SessionStore.SessionCreateRequest(workspace, "test", "model", null, "plan", "agent"));
            var executions = new RuntimeExecutionStore(temp.resolve("tasks.db"))) {
            var held = executions.enqueue(workspace, parent.sessionId(), "original", null);
            var claim = executions.claimNext(workspace).orElseThrow();
            var queued = executions.enqueue(workspace, parent.sessionId(), "inspect parser API_\u001b[31mKEY=private-token-123456\u001b[0m", null);
            // Set a held gate through the real owned-workspace freeze operation.
            try (var owner = WorkspaceOwnerLease.acquire(temp.resolve("locks"), identity, "new-owner")) {
                executions.freezeForUser(owner);
                var request = executions.enqueue(workspace, parent.sessionId(), "where are we", null);
                var running = executions.claimCandidate(owner, request.id()).orElseThrow();
                var plans = new PlanStateStore(temp.resolve("plans.db"));
                var plan = new ExecutionPlan("plan", "private goal");
                plan.addTask(new Task("t", "private description", Task.TaskType.FILE_READ));
                plan.computeExecutionOrder();
                plan.getTask("t").markStarted();
                plans.savePlan(workspace, parent.sessionId(), held.id(), "policy", plan);
                var calls = new AtomicInteger();
                var provider = new RecoveryContextProvider(executions, plans, sessions, TaskRecoveryStore.openReadOnly(plans.dbPath()), (w, p, c) -> {
                    owner.verifyIdentity();
                    calls.incrementAndGet();
                });
                var boundary = new RecoveryContextProvider.ReadBoundary(identity, parent.sessionId(), running.claim());
                byte[] before = Files.readAllBytes(plans.dbPath());
                var result = provider.readRecoveryContext(boundary);
                assertTrue(calls.get() >= 2);
                assertEquals(2, result.executions().stream().filter(RecoveryExecutionState::continuationCandidate).count());
                assertTrue(parent.projection().conversationMessages().isEmpty());
                assertEquals("original", result.executions().stream().filter(e -> e.executionId().equals(held.id())).findFirst().orElseThrow().goalExcerpt());
                String queuedGoal = result.executions().stream().filter(e -> e.executionId().equals(queued.id())).findFirst().orElseThrow().goalExcerpt();
                assertTrue(queuedGoal.contains("inspect parser"));assertFalse(queuedGoal.contains("private-token"));assertFalse(queuedGoal.contains("\u001b"));
                var formatted = new com.codeagent.prompt.RecoveryContextFormatter().formatForParent(result,new RecoveryContextBudget(30000,4096,2000),running.id());
                assertTrue(formatted.content().contains("original"));assertTrue(formatted.content().contains("inspect parser"));
                assertFalse(formatted.content().contains("where are we"));
                assertFalse(result.executions().stream().filter(e -> e.executionId().equals(running.id())).findFirst().orElseThrow().continuationCandidate());
                assertEquals("RUNNING", result.plans().get(0).tasks().get(0).recordedStatus());
                assertEquals("INTERRUPTED", result.plans().get(0).tasks().get(0).recoveryStatus());
                assertArrayEquals(before, Files.readAllBytes(plans.dbPath()));
                assertFalse(result.toString().contains("private"));
                assertTrue(provider.isCurrent(result, boundary));
                try (var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+temp.resolve("tasks.db"));var update=c.prepareStatement("UPDATE runtime_executions SET submitted_input=? WHERE id=?")) {
                    update.setString(1,"changed parser goal");update.setString(2,queued.id());update.executeUpdate();
                }
                assertFalse(provider.isCurrent(result,boundary),"same sequence/status/time does not hide changed selected source goal");
                plan.getTask("t").markCompleted("recorded only");
                plans.checkpointTask(plan.getId(), plan.getTask("t"));
                assertFalse(provider.isCurrent(result, boundary));
                plan.markCompleted();
                plans.checkpointPlan(plan);
                var legacy = new ExecutionPlan("legacy", "legacy private");
                legacy.addTask(new Task("pending", "pending", Task.TaskType.ANALYSIS));
                legacy.computeExecutionOrder();
                plans.savePlan(workspace, parent.sessionId(), legacy);
                var withLegacy = provider.readRecoveryContext(boundary);
                assertTrue(withLegacy.plans().stream().anyMatch(p -> p.planId().equals("legacy") && p.executionId() == null));
                assertTrue(withLegacy.warnings().stream().anyMatch(w -> w.code() == RecoveryWarning.Code.LEGACY_UNBOUND));
                var denied = new RecoveryContextProvider(executions, plans, sessions, TaskRecoveryStore.openReadOnly(plans.dbPath()), (w, p, c) -> {
                    throw new java.io.IOException("denied");
                });
                assertThrows(java.io.IOException.class, () -> denied.readRecoveryContext(boundary));
                assertThrows(java.io.IOException.class, () -> provider.readRecoveryContext(new RecoveryContextProvider.ReadBoundary(identity, "other", running.claim())));
                executions.freezeForUser(owner);
                assertThrows(java.io.IOException.class, () -> provider.isCurrent(result, boundary));
            }
        }
    }
}
