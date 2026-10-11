package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.RoutingSource;
import com.codeagent.history.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionFinalizerTest {

    @Test void retryConvergesWhenLedgerEndSucceededButSqlTerminalFailed(@TempDir Path workspace) throws Exception {
        Path database = workspace.resolve("executions.db");
        try (SessionStore sessions = SessionStore.open(workspace.resolve("history"));
             var session = sessions.create(new SessionStore.SessionCreateRequest(workspace, "test", "test", null, "react", "test"));
             var store = new RuntimeExecutionStore(database);
             var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            store.enqueue(workspace, session.sessionId(), "读取文件", ExecutionMode.REACT);
            var execution = store.claimNext(workspace).orElseThrow();
            execution = store.commitRoutingDecision(execution.claim(), ExecutionMode.REACT, RoutingSource.EXPLICIT);
            var claim = execution.claim();
            var finalizer = new ExecutionFinalizer(store);
            finalizer.ensureStarted(execution, session);
            statement.execute("CREATE TRIGGER fail_terminal BEFORE UPDATE OF status ON runtime_executions WHEN NEW.status='completed' BEGIN SELECT RAISE(ABORT,'test fault'); END");
            assertThrows(java.sql.SQLException.class, () -> finalizer.finish(claim, TopLevelExecutionResult.succeeded("done"), session));
            assertEquals(ExecutionStatus.RUNNING, store.find(execution.id()).orElseThrow().status());
            long sequence = session.projection().executionEnvelopes().get(execution.id()).endSequence();
            statement.execute("DROP TRIGGER fail_terminal");
            assertEquals(ExecutionStatus.COMPLETED, finalizer.finish(claim, TopLevelExecutionResult.succeeded("done"), session).status());
            assertEquals(sequence, session.projection().executionEnvelopes().get(execution.id()).endSequence());
        }
    }

    @Test void rejectsOldAttemptBeforeAppendingAnyLedgerBoundary(@TempDir Path workspace) throws Exception {
        try (SessionStore sessions = SessionStore.open(workspace.resolve("history"));
             var session = sessions.create(new SessionStore.SessionCreateRequest(workspace, "test", "test", null, "react", "test"));
             RuntimeExecutionStore store = new RuntimeExecutionStore(workspace.resolve("execution.db"))) {
            var submitted = store.enqueue(workspace, session.sessionId(), "读取文件", ExecutionMode.REACT);
            var old = store.claimNext(workspace, "old").orElseThrow();
            store.interruptForRecovery(old.claim(), "test");
            var current = store.claimNext(workspace, "new").orElseThrow();
            var finalizer = new ExecutionFinalizer(store);
            assertThrows(IllegalStateException.class, () -> finalizer.ensureStarted(old, session));
            assertThrows(IllegalStateException.class, () -> finalizer.finish(old.claim(), TopLevelExecutionResult.succeeded("late"), session));
            assertFalse(session.projection().executionEnvelopes().containsKey(submitted.id()));
            assertEquals(current.claim(), store.find(submitted.id()).orElseThrow().claim());
        }
    }

    @Test
    void appendsOneEnvelopeEndAndCommitsMatchingTerminalState(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     workspace, "provider", "model", null, "react", "agent"));
             RuntimeExecutionStore executions = new RuntimeExecutionStore(tempDir.resolve("tasks.db"))) {
            RuntimeExecution execution = executions.enqueue(
                    workspace, session.sessionId(), "raw", ExecutionMode.REACT);
            execution = executions.claimNext(workspace).orElseThrow();
            execution = executions.commitRoutingDecision(
                    execution.claim(), ExecutionMode.REACT, RoutingSource.EXPLICIT);
            ExecutionFinalizer finalizer = new ExecutionFinalizer(executions);

            finalizer.ensureStarted(execution, session);
            RuntimeExecution terminal = finalizer.finish(
                    execution.claim(), TopLevelExecutionResult.succeeded("done"), session);
            RuntimeExecution repeated = finalizer.finish(
                    execution.claim(), TopLevelExecutionResult.succeeded("done"), session);

            assertEquals(ExecutionStatus.COMPLETED, terminal.status());
            assertEquals(terminal, repeated);
            var envelope = session.projection().executionEnvelopes().get(execution.id());
            assertNotNull(envelope.endSequence());
            assertEquals("succeeded", envelope.outcome());
            assertEquals("completed", envelope.status());
        }
    }

    @Test
    void durableCancelOverridesSuccessfulRunnerResult(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     workspace, "provider", "model", null, "react", "agent"));
             RuntimeExecutionStore executions = new RuntimeExecutionStore(tempDir.resolve("tasks.db"))) {
            RuntimeExecution execution = executions.enqueue(workspace, session.sessionId(), "raw", null);
            execution = executions.claimNext(workspace).orElseThrow();
            execution = executions.commitRoutingDecision(
                    execution.claim(), ExecutionMode.REACT, RoutingSource.AUTO_MODEL);
            ExecutionFinalizer finalizer = new ExecutionFinalizer(executions);
            finalizer.ensureStarted(execution, session);
            executions.requestCancel(execution.id());

            RuntimeExecution terminal = finalizer.finish(
                    execution.claim(), TopLevelExecutionResult.succeeded("late success"), session);

            assertEquals(ExecutionStatus.CANCELED, terminal.status());
            assertEquals(ExecutionOutcome.CANCELED, terminal.outcome());
            assertEquals("canceled",
                    session.projection().executionEnvelopes().get(execution.id()).outcome());
        }
    }
}
