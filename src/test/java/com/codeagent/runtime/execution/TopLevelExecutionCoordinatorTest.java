package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.RoutingDecision;
import com.codeagent.agent.RoutingSource;
import com.codeagent.history.SessionProjection;
import com.codeagent.runtime.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TopLevelExecutionCoordinatorTest {

    @Test
    void resolvesOnceRoutesRawInputAndAcksModeBeforeExecution(@TempDir Path tempDir) throws Exception {
        AtomicReference<String> routedInput = new AtomicReference<>();
        AtomicReference<String> deliveredInput = new AtomicReference<>();
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"))) {
            RuntimeExecution enqueued = store.enqueue(tempDir, "session", "raw @file", null);
            RuntimeExecution claimed = store.claimNext(tempDir).orElseThrow();
            TopLevelExecutionCoordinator coordinator = new TopLevelExecutionCoordinator(
                    store,
                    ignored -> "expanded file contents",
                    (submittedInput, history) -> {
                        routedInput.set(submittedInput);
                        return new RoutingDecision(ExecutionMode.PLAN, RoutingSource.AUTO_MODEL, null);
                    },
                    ignored -> List.<SessionProjection.ConversationNode>of(),
                    (mode, input, action) -> action.run(),
                    (execution, resolved, token) -> fail("ReAct must not run"),
                    (execution, resolved, token) -> {
                        RuntimeExecution durable = store.find(execution.id()).orElseThrow();
                        assertEquals(ExecutionMode.PLAN, durable.selectedMode(),
                                "mode must be durable before Plan side effects");
                        deliveredInput.set(resolved);
                        return TopLevelExecutionResult.succeeded("planned");
                    });

            TopLevelExecutionResult result = coordinator.run(claimed, new CancellationToken());

            assertEquals("raw @file", routedInput.get());
            assertEquals("expanded file contents", deliveredInput.get());
            assertEquals(ExecutionMode.PLAN, result.mode());
            assertEquals(RoutingSource.AUTO_MODEL, result.routingSource());
            assertEquals("expanded file contents",
                    store.find(enqueued.id()).orElseThrow().resolvedTaskInput());
        }
    }

    @Test
    void explicitModeSkipsRouterAndResolvedInputIsIdempotent(@TempDir Path tempDir) throws Exception {
        AtomicReference<Integer> resolves = new AtomicReference<>(0);
        try (RuntimeExecutionStore store = new RuntimeExecutionStore(tempDir.resolve("tasks.db"))) {
            RuntimeExecution execution = store.enqueue(tempDir, "session", "raw", ExecutionMode.REACT);
            RuntimeExecution claimed = store.claimNext(tempDir).orElseThrow();
            TopLevelExecutionCoordinator coordinator = new TopLevelExecutionCoordinator(
                    store,
                    ignored -> {
                        resolves.set(resolves.get() + 1);
                        return "resolved";
                    },
                    (input, history) -> fail("explicit mode must skip router"),
                    ignored -> List.of(),
                    (mode, input, action) -> action.run(),
                    (current, resolved, token) -> TopLevelExecutionResult.succeeded(resolved),
                    (current, resolved, token) -> fail("Plan must not run"));

            TopLevelExecutionResult first = coordinator.run(claimed, new CancellationToken());
            RuntimeExecution persisted = store.find(execution.id()).orElseThrow();
            TopLevelExecutionResult resumed = coordinator.run(persisted, new CancellationToken());

            assertEquals(1, resolves.get());
            assertEquals(ExecutionMode.REACT, first.mode());
            assertEquals(RoutingSource.EXPLICIT, first.routingSource());
            assertEquals(first, resumed);
        }
    }
}
