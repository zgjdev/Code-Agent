package com.codeagent.tool;

import com.codeagent.model.recovery.*;
import com.codeagent.tool.ToolRegistry.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskObservationLifecycleTest {

    @TempDir
    Path temp;

    private TaskAttemptBinding binding(String id) {
        return new TaskAttemptBinding(temp.toString(), "parent", "execution", "plan", id, 1, id, "child-" + id, "owner", 1, null, 0);
    }

    @Test
    void fourConcurrentCallsRetainExplicitIdentityOriginalOrderAndDurableStart() throws Exception {
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var started = ConcurrentHashMap.<String>newKeySet();
        var seen = new ConcurrentHashMap<String, Integer>();
        var registry = new ToolRegistry() {

            @Override
            public ToolOutput executeToolOutput(String name, String args) {
                String id;
                try {
                    id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(args).path("id").asText();
                } catch (Exception e) {
                    throw new IllegalStateException();
                }
                assertTrue(started.contains(id));
                int n = active.incrementAndGet();
                peak.accumulateAndGet(n, Math::max);
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                active.decrementAndGet();
                return ToolOutput.text(id);
            }
        };
        registry.setProjectPath(temp.toString());
        TaskToolObserver observer = new TaskToolObserver() {

            public void started(TaskObservationContext c, ToolInvocation i) {
                assertEquals("task", c.binding().taskId());
                started.add(i.id());
                seen.put(i.id(), c.callOrdinal());
            }

            public void completed(TaskObservationContext c, ToolInvocation i, ToolExecutionResult r) {
                assertTrue(started.contains(i.id()));
            }
        };
        var calls = new ArrayList<ToolInvocation>();
        for (int i = 0; i < 4; i++) calls.add(new ToolInvocation("c" + i, "probe", "{\"id\":\"c" + i + "\"}"));
        var policy = TurnToolPolicy.forExplicitTask("run local probes");
        var exposure = policy.expose(List.of(new com.codeagent.llm.LlmClient.Tool("probe", "probe", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())));
        var result = policy.execute(registry, calls, exposure, new TaskObservationContext(binding("task"), "batch", 0), observer);
        assertEquals(List.of("c0", "c1", "c2", "c3"), result.stream().map(ToolExecutionResult::id).toList());
        assertEquals(Map.of("c0", 0, "c1", 1, "c2", 2, "c3", 3), seen);
        assertTrue(peak.get() > 1);
    }

    @Test
    void startFailureDeniesEffectAndResultFailurePreservesUnknown() throws Exception {
        var effects = new AtomicInteger();
        var registry = new ToolRegistry() {

            @Override
            public ToolOutput executeToolOutput(String n, String a) {
                effects.incrementAndGet();
                return ToolOutput.text("actual effect outcome");
            }
        };
        registry.setProjectPath(temp.toString());
        TaskToolObserver startFail = new TaskToolObserver() {

            public void started(TaskObservationContext c, ToolInvocation i) throws Exception {
                throw new IOException("private");
            }

            public void completed(TaskObservationContext c, ToolInvocation i, ToolExecutionResult r) {
            }
        };
        var context = new TaskObservationContext(binding("task"), "batch", 0);
        var denied = registry.executeTools(List.of(new ToolInvocation("x", "probe", "{}", new TaskToolObservation(context, startFail)))).get(0);
        assertFalse(denied.successful());
        assertEquals(0, effects.get());
        assertFalse(denied.result().contains("private"));
        TaskToolObserver resultFail = new TaskToolObserver() {

            public void started(TaskObservationContext c, ToolInvocation i) {
            }

            public void completed(TaskObservationContext c, ToolInvocation i, ToolExecutionResult r) throws Exception {
                throw new IOException("private");
            }
        };
        var unknown = registry.executeTools(List.of(new ToolInvocation("y", "probe", "{}", new TaskToolObservation(context, resultFail)))).get(0);
        assertEquals(1, effects.get());
        assertTrue(unknown.observationUnknown());
        assertFalse(unknown.successful());
        assertEquals("actual effect outcome", unknown.result());
        var collector = new com.codeagent.agent.TaskEvidenceCollector();
        collector.observeTools(List.of(new ToolInvocation("y", "probe", "{}")), List.of(unknown), temp);
        assertEquals(com.codeagent.agent.EvidenceStatus.MISSING, collector.snapshot().get(0).status());
    }

    @Test
    void nativeExitMetadataIsTypedNotParsedAssistantText() {
        var registry = new ToolRegistry();
        registry.setProjectPath(temp.toString());
        var result = registry.executeTools(List.of(new ToolInvocation("x", "execute_command", "{\"command\":\"exit 7\"}"))).get(0);
        assertEquals(7, result.exitCode());
        assertFalse(result.successful());
    }
}
