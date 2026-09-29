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
                    execution.id(), ExecutionMode.REACT, RoutingSource.EXPLICIT);
            ExecutionFinalizer finalizer = new ExecutionFinalizer(executions);

            finalizer.ensureStarted(execution, session);
            RuntimeExecution terminal = finalizer.finish(
                    execution.id(), TopLevelExecutionResult.succeeded("done"), session);
            RuntimeExecution repeated = finalizer.finish(
                    execution.id(), TopLevelExecutionResult.succeeded("done"), session);

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
                    execution.id(), ExecutionMode.REACT, RoutingSource.AUTO_MODEL);
            ExecutionFinalizer finalizer = new ExecutionFinalizer(executions);
            finalizer.ensureStarted(execution, session);
            executions.requestCancel(execution.id());

            RuntimeExecution terminal = finalizer.finish(
                    execution.id(), TopLevelExecutionResult.succeeded("late success"), session);

            assertEquals(ExecutionStatus.CANCELED, terminal.status());
            assertEquals(ExecutionOutcome.CANCELED, terminal.outcome());
            assertEquals("canceled",
                    session.projection().executionEnvelopes().get(execution.id()).outcome());
        }
    }
}
