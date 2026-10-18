package com.codeagent.history;

import com.codeagent.model.recovery.*;
import com.codeagent.plan.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskTranscriptReaderTest {

    @TempDir
    Path temp;

    private static final ObjectMapper JSON = new ObjectMapper();

    private void message(SessionStore.SessionHandle child, String type, String json) throws Exception {
        var payload = JSON.createObjectNode();
        payload.set("message", JSON.readTree(json));
        if (SessionEvent.Types.TOOL_RESULT.equals(type))
            payload.put("invocationId", payload.path("message").path("toolCallId").asText());
        child.append(new SessionEventDraft(type, "plan", child.manifest().actor(), "test", false, SessionEvent.SurfaceOperation.append(), payload));
    }

    private void importGroup(SessionStore.SessionHandle child, String sourceAttempt, RecoveredMessageGroup group) throws Exception {
        var payload = JSON.createObjectNode();
        payload.putObject("message").put("role", "assistant").put("content", "untrusted copy must be ignored");
        payload.putObject("recoveryOrigin").put("attemptId", sourceAttempt)
                .put("childSessionId", group.canonicalOriginChildId()).put("firstSequence", group.firstSequence())
                .put("lastSequence", group.lastSequence()).put("contentHash", group.contentHash());
        child.append(new SessionEventDraft(SessionEvent.Types.ASSISTANT_MESSAGE, "plan", child.manifest().actor(), "recovery_import", false, SessionEvent.SurfaceOperation.append(), payload));
    }

    @Test
    void canonicalImportsValidateWholeCompletedSourceBeforeSelectingEarlyAndFinalGroups() throws Exception {
        var plans = new PlanStateStore(temp.resolve("plans.db"));
        var plan = new ExecutionPlan("p", "goal");
        plan.addTask(new Task("task", "description", Task.TaskType.FILE_READ));
        plan.computeExecutionOrder();
        try (var sessions = SessionStore.open(temp.resolve("history"));
             var parent = sessions.create(new SessionStore.SessionCreateRequest(temp, "test", "model", null, "plan", "agent"));
             var source = parent.createChild("plan", "task:task");
             var target = parent.createChild("plan", "task:task");
             var next = parent.createChild("plan", "task:task")) {
            plans.savePlan(temp, parent.sessionId(), "e", "policy", plan);
            var attempts = new TaskRecoveryStore(plans.dbPath());
            var a = attempts.bindAttempt(temp, parent.sessionId(), "e", "p", "task", source.sessionId(), null, "owner", 1);
            message(source, SessionEvent.Types.ASSISTANT_MESSAGE, "{\"role\":\"assistant\",\"content\":\"early\"}");
            message(source, SessionEvent.Types.ASSISTANT_MESSAGE, "{\"role\":\"assistant\",\"toolCalls\":[{\"id\":\"call\",\"function\":{\"name\":\"list_dir\",\"arguments\":\"{\\\"path\\\":\\\".\\\"}\"}}]}");
            var registry = new com.codeagent.tool.ToolRegistry();
            registry.setProjectPath(temp.toString());
            var observer = new TaskRecoveryObserver(attempts, source, a);
            var context = new TaskObservationContext(a, "batch", 0);
            var invocation = new com.codeagent.tool.ToolRegistry.ToolInvocation("call", "list_dir", "{\"path\":\".\"}", new com.codeagent.tool.TaskToolObservation(context, observer));
            var result = registry.executeTools(List.of(invocation)).get(0);
            assertTrue(result.successful());
            message(source, SessionEvent.Types.TOOL_RESULT, "{\"role\":\"tool\",\"toolCallId\":\"call\",\"content\":\"safe tool result\"}");
            observer.checkpoint("TOOL_BATCH");
            message(source, SessionEvent.Types.ASSISTANT_MESSAGE, "{\"role\":\"assistant\",\"content\":\"final\"}");
            source.markClosed("completed");
            observer.checkpoint("TASK_RESULT");
            var reader = new TaskTranscriptReader(sessions, attempts);
            var original = reader.read(a, -1);
            assertEquals(3, original.groups().size());
            assertTrue(attempts.readCheckpoint(a).childLastSequence() > original.groups().get(2).lastSequence());
            var b = attempts.bindAttempt(temp, parent.sessionId(), "e", "p", "task", target.sessionId(), a.attemptId(), "owner", 2);
            importGroup(target, a.attemptId(), original.groups().get(0));
            importGroup(target, a.attemptId(), original.groups().get(2));
            var imported = reader.read(b, -1);
            assertEquals(List.of(original.groups().get(0), original.groups().get(2)), imported.groups());
            var c = attempts.bindAttempt(temp, parent.sessionId(), "e", "p", "task", next.sessionId(), b.attemptId(), "owner", 3);
            importGroup(next, b.attemptId(), original.groups().get(0));
            importGroup(next, a.attemptId(), original.groups().get(0));
            importGroup(next, b.attemptId(), original.groups().get(2));
            assertEquals(imported.groups(), reader.read(c, -1).groups(), "multihop canonical origins deduplicate");
            var finalGroup = original.groups().get(2);
            importGroup(next, a.attemptId(), new RecoveredMessageGroup(finalGroup.canonicalOriginChildId(),
                    finalGroup.sourceAttemptId(), finalGroup.firstSequence(), finalGroup.lastSequence(), "forged-hash",
                    finalGroup.completeness(), finalGroup.messages(), finalGroup.redacted()));
            assertThrows(java.io.IOException.class, () -> reader.read(c, -1), "canonical group hash remains mandatory");
            var actualStart = attempts.readInvocationSources(a).get(0);
            try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + plans.dbPath()); var s = db.createStatement()) {
                s.executeUpdate("UPDATE task_execution_attempts SET source_attempt_id='" + c.attemptId() + "' WHERE attempt_id='" + a.attemptId() + "'");
                assertThrows(java.io.IOException.class, () -> reader.read(b, -1), "source-chain loop is never bypassed by memoization");
                s.executeUpdate("UPDATE task_execution_attempts SET source_attempt_id=NULL WHERE attempt_id='" + a.attemptId() + "'");
                s.executeUpdate("UPDATE task_recovery_invocations SET start_hash='forged-hash' WHERE attempt_id='" + a.attemptId() + "'");
                assertThrows(java.io.IOException.class, () -> reader.read(b, -1), "typed source hash mismatch remains rejected across imports");
                s.executeUpdate("UPDATE task_recovery_invocations SET start_hash='" + actualStart.contentHash() + "',start_sequence=1 WHERE attempt_id='" + a.attemptId() + "'");
                assertThrows(java.io.IOException.class, () -> reader.read(b, -1), "an existing event that is not the indexed typed start cannot supply its reference");
                s.executeUpdate("UPDATE task_recovery_invocations SET start_sequence=" + actualStart.sequence() + " WHERE attempt_id='" + a.attemptId() + "'");
                s.executeUpdate("UPDATE task_recovery_invocations SET result_sequence=9999 WHERE attempt_id='" + a.attemptId() + "'");
            }
            assertThrows(java.io.IOException.class, () -> reader.read(b, -1), "real references past full committed source must remain rejected");
        }
    }

    @Test
    void readsAssistantEmbeddedToolCallsOnlyAsWholeGroupsAtFixedPrefix() throws Exception {
        var plans = new PlanStateStore(temp.resolve("plans.db"));
        var plan = new ExecutionPlan("p", "goal");
        plan.addTask(new Task("task", "description", Task.TaskType.FILE_READ));
        plan.computeExecutionOrder();
        try (var sessions = SessionStore.open(temp.resolve("history"));
            var parent = sessions.create(new SessionStore.SessionCreateRequest(temp, "test", "model", null, "plan", "agent"));
            var child = parent.createChild("plan", "task:task")) {
            plans.savePlan(temp, parent.sessionId(), "e", "policy", plan);
            var store = new TaskRecoveryStore(plans.dbPath());
            var a = store.bindAttempt(temp, parent.sessionId(), "e", "p", "task", child.sessionId(), null, "owner", 1);
            message(child, SessionEvent.Types.SYSTEM_MESSAGE, "{\"role\":\"system\",\"content\":\"old system\"}");
            message(child, SessionEvent.Types.ASSISTANT_MESSAGE, "{\"role\":\"assistant\",\"content\":\"checking\",\"reasoningContent\":\"private reasoning\",\"toolCalls\":[{\"id\":\"call\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]}");
            message(child, SessionEvent.Types.TOOL_RESULT, "{\"role\":\"tool\",\"toolCallId\":\"call\",\"content\":\"safe result\"}");
            long watermark = child.manifest().lastEventSequence();
            message(child, SessionEvent.Types.ASSISTANT_MESSAGE, "{\"role\":\"assistant\",\"toolCalls\":[{\"id\":\"pending\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]}");
            var reader = new TaskTranscriptReader(sessions, store);
            var fixed = reader.read(a, watermark);
            assertEquals(1, fixed.groups().size());
            assertEquals(2, fixed.groups().get(0).messages().size());
            assertEquals("safe result", fixed.groups().get(0).messages().get(1).content());
            assertFalse(fixed.toString().contains("safe result"));
            assertFalse(fixed.groups().toString().contains("checking"));
            var full = reader.read(a, -1);
            assertEquals(1, full.groups().size());
            assertEquals(1, full.unknowns().size());
            assertEquals(RecoveryCompleteness.PARTIAL, full.completeness());
            assertThrows(java.io.IOException.class, () -> reader.read(a, 999));
        }
    }

    @Test
    void secretsOrAttachmentsOmitWholeGroupAndForgedBindingRejected() throws Exception {
        var plans = new PlanStateStore(temp.resolve("plans.db"));
        var plan = new ExecutionPlan("p", "goal");
        plan.addTask(new Task("task", "d", Task.TaskType.FILE_READ));
        plan.computeExecutionOrder();
        try (var sessions = SessionStore.open(temp.resolve("history"));
            var parent = sessions.create(new SessionStore.SessionCreateRequest(temp, "test", "model", null, "plan", "agent"));
            var child = parent.createChild("plan", "task:task")) {
            plans.savePlan(temp, parent.sessionId(), "e", "policy", plan);
            var store = new TaskRecoveryStore(plans.dbPath());
            var a = store.bindAttempt(temp, parent.sessionId(), "e", "p", "task", child.sessionId(), null, "owner", 1);
            message(child, SessionEvent.Types.ASSISTANT_MESSAGE, "{\"role\":\"assistant\",\"content\":\"API_KEY=synthetic-secret-value\",\"toolCalls\":[{\"id\":\"x\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]}");
            message(child, SessionEvent.Types.TOOL_RESULT, "{\"role\":\"tool\",\"toolCallId\":\"x\",\"content\":\"normal\"}");
            var reader = new TaskTranscriptReader(sessions, store);
            var transcript = reader.read(a, -1);
            assertTrue(transcript.groups().isEmpty());
            assertEquals(RecoveryCompleteness.PARTIAL, transcript.completeness());
            var forged = new TaskAttemptBinding(a.workspace(), a.parentSessionId(), "other", a.planId(), a.taskId(), a.attemptNo(), a.attemptId(), a.childSessionId(), a.runtimeOwnerId(), a.runtimeAttempt(), a.sourceAttemptId(), a.createdAt());
            assertThrows(java.io.IOException.class, () -> reader.read(forged, -1));
        }
    }

    @Test
    void forcedTypedResultIsRecoveredWhenDatabaseIndexCommitFailsAfterEffect() throws Exception {
        var plans = new PlanStateStore(temp.resolve("plans.db"));
        var plan = new ExecutionPlan("p", "goal");
        plan.addTask(new Task("task", "description", Task.TaskType.FILE_READ));
        plan.computeExecutionOrder();
        try (var sessions = SessionStore.open(temp.resolve("history"));
            var parent = sessions.create(new SessionStore.SessionCreateRequest(temp, "test", "model", null, "plan", "agent"));
            var child = parent.createChild("plan", "task:task")) {
            plans.savePlan(temp, parent.sessionId(), "e", "policy", plan);
            var attempts = new TaskRecoveryStore(plans.dbPath()) {

                @Override
                public synchronized void recordResult(TaskAttemptBinding a, String call, int ordinal, String tool, boolean ok, boolean timeout, Integer exit, long sequence, String hash) throws java.sql.SQLException {
                    throw new java.sql.SQLException("synthetic crash after child force");
                }
            };
            var binding = attempts.bindAttempt(temp, parent.sessionId(), "e", "p", "task", child.sessionId(), null, "owner", 1);
            var observer = new TaskRecoveryObserver(attempts, child, binding);
            var registry = new com.codeagent.tool.ToolRegistry();
            registry.setProjectPath(temp.toString());
            var context = new TaskObservationContext(binding, "batch", 0);
            var invocation = new com.codeagent.tool.ToolRegistry.ToolInvocation("call", "execute_command", "{\"command\":\"exit 0\"}", new com.codeagent.tool.TaskToolObservation(context, observer));
            var result = registry.executeTools(List.of(invocation)).get(0);
            assertTrue(result.observationUnknown());
            assertFalse(result.successful());
            assertEquals(0, result.exitCode());
            assertEquals(1, attempts.readCheckpoint(binding).unknowns().size());
            byte[] log = Files.readAllBytes(child.sessionDirectory().resolve("events.jsonl"));
            var recovered = new TaskTranscriptReader(sessions, attempts).read(binding, -1);
            assertEquals(1, recovered.facts().size());
            assertTrue(recovered.unknowns().isEmpty());
            assertEquals(RecoveryFact.Kind.COMMAND_SUCCEEDED, recovered.facts().get(0).kind());
            assertEquals(RecoveryFact.VerificationClass.HISTORICAL, recovered.facts().get(0).verificationClass());
            assertArrayEquals(log, Files.readAllBytes(child.sessionDirectory().resolve("events.jsonl")));
            assertEquals(1, attempts.readCheckpoint(binding).unknowns().size(), "reader must not repair DB index");
        }
    }

    @Test
    void oversizedValidJsonArgumentsOmitWholeGroupWithoutChangingArguments() throws Exception {
        var plans = new PlanStateStore(temp.resolve("plans.db"));
        var plan = new ExecutionPlan("p", "goal");
        plan.addTask(new Task("task", "description", Task.TaskType.FILE_READ));
        plan.computeExecutionOrder();
        try (var sessions = SessionStore.open(temp.resolve("history"));
            var parent = sessions.create(new SessionStore.SessionCreateRequest(temp, "test", "model", null, "plan", "agent"));
            var child = parent.createChild("plan", "task:task")) {
            plans.savePlan(temp, parent.sessionId(), "e", "policy", plan);
            var attempts = new TaskRecoveryStore(plans.dbPath());
            var binding = attempts.bindAttempt(temp, parent.sessionId(), "e", "p", "task", child.sessionId(), null, "owner", 1);
            String arguments = JSON.writeValueAsString(Map.of("value", "x".repeat(33000)));
            var assistant = JSON.createObjectNode().put("role", "assistant");
            assistant.putArray("toolCalls").addObject().put("id", "large").putObject("function").put("name", "read_file").put("arguments", arguments);
            message(child, SessionEvent.Types.ASSISTANT_MESSAGE, assistant.toString());
            message(child, SessionEvent.Types.TOOL_RESULT, "{\"role\":\"tool\",\"toolCallId\":\"large\",\"content\":\"safe\"}");
            var transcript = new TaskTranscriptReader(sessions, attempts).read(binding, -1);
            assertTrue(transcript.groups().isEmpty());
            assertEquals(RecoveryCompleteness.PARTIAL, transcript.completeness());
            assertEquals(1, transcript.warnings().get(0).omittedCount());
            assertThrows(IllegalArgumentException.class, () -> new RecoveryMessage.ToolCall("large", "read_file", arguments));
            var state = new RecoveryTaskState("p", "t", 0, "a".repeat(2036) + "😀".repeat(20), "PENDING", "PENDING", List.of(), null, List.of(), List.of(), List.of(), RecoveryCompleteness.METADATA_ONLY);
            assertTrue(state.description().endsWith("[TRUNCATED]"));
            assertTrue(state.description().codePoints().noneMatch(c -> c >= 0xd800 && c <= 0xdfff));
        }
    }
}
