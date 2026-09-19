package com.codeagent.agent;

import com.codeagent.lsp.LspDiagnosticReport;
import com.codeagent.plan.EvidenceType;
import com.codeagent.tool.ToolRegistry.ToolExecutionResult;
import com.codeagent.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskEvidenceCollectorTest {
    @Test
    void observesDiffAndFailedWriteFromToolResults() {
        TaskEvidenceCollector collector = new TaskEvidenceCollector();
        collector.observeTools(
                List.of(new ToolInvocation("1", "write_file", "{\"path\":\"src/App.java\"}")),
                List.of(result("1", "write_file", true, "written")), null);
        collector.observeTools(
                List.of(new ToolInvocation("2", "write_file", "{\"path\":\"src/Other.java\"}")),
                List.of(result("2", "write_file", false, "failed")), null);

        assertEquals(EvidenceStatus.PASSED, collector.snapshot().stream()
                .filter(e -> e.type() == EvidenceType.DIFF && e.status() == EvidenceStatus.PASSED)
                .findFirst().orElseThrow().status());
        assertTrue(collector.snapshot().stream().anyMatch(e -> e.type() == EvidenceType.DIFF
                && e.status() == EvidenceStatus.FAILED));
    }

    @Test
    void classifiesOnlyObservedBuildAndTestCommands() {
        TaskEvidenceCollector collector = new TaskEvidenceCollector();
        collector.observeTools(
                List.of(
                        new ToolInvocation("1", "execute_command", "{\"command\":\"mvn test\"}"),
                        new ToolInvocation("2", "execute_command", "{\"command\":\"mvn package\"}"),
                        new ToolInvocation("3", "execute_command", "{\"command\":\"echo tests passed\"}")),
                List.of(
                        result("1", "execute_command", true, "ok"),
                        result("2", "execute_command", true, "ok"),
                        result("3", "execute_command", true, "tests passed")), null);

        assertTrue(collector.snapshot().stream().anyMatch(e -> e.type() == EvidenceType.TEST
                && e.status() == EvidenceStatus.PASSED));
        assertTrue(collector.snapshot().stream().anyMatch(e -> e.type() == EvidenceType.BUILD
                && e.status() == EvidenceStatus.PASSED));
        assertEquals(0, collector.snapshot().stream().filter(e -> e.type() == EvidenceType.TEST).count() - 1);
    }

    @Test
    void lspErrorsBecomeFailedEvidenceWithCounts() {
        TaskEvidenceCollector collector = new TaskEvidenceCollector();
        collector.observeLsp(new LspDiagnosticReport("prompt", "display", 2, 1));

        TaskEvidence evidence = collector.snapshot().stream()
                .filter(item -> item.type() == EvidenceType.LSP).findFirst().orElseThrow();
        assertEquals(EvidenceStatus.FAILED, evidence.status());
        assertTrue(evidence.summary().contains("errors=2"));
    }

    private static ToolExecutionResult result(String id, String name, boolean success, String text) {
        return new ToolExecutionResult(id, name, "{}", text, 1, false, List.of(), success, List.of());
    }
}
