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
    void writeFileResultsDoNotForgeDiffEvidence() {
        TaskEvidenceCollector collector = new TaskEvidenceCollector();
        collector.observeTools(
                List.of(new ToolInvocation("1", "write_file", "{\"path\":\"src/App.java\"}")),
                List.of(result("1", "write_file", true, "written")), null);

        assertEquals(0, collector.snapshot().stream()
                .filter(e -> e.type() == EvidenceType.DIFF)
                .count());
        assertTrue(collector.snapshot().stream().anyMatch(e -> e.type() == EvidenceType.TOOL_RESULT
                && e.status() == EvidenceStatus.PASSED
                && "write_file".equals(e.source())));
    }

    @Test
    void realDiffEvidenceCarriesStatsAndChangedPaths() {
        TaskEvidenceCollector collector = new TaskEvidenceCollector();
        collector.observeDiff(new TaskWorkspaceDiffTracker.DiffSummary(
                true, 1, 2, 1, 0, List.of("src/App.java"),
                "0123456789abcdef0123456789abcdef"));

        TaskEvidence diff = collector.snapshot().stream()
                .filter(e -> e.type() == EvidenceType.DIFF)
                .findFirst().orElseThrow();
        assertEquals(EvidenceStatus.PASSED, diff.status());
        assertEquals(List.of("src/App.java"), diff.relatedPaths());
        assertTrue(diff.summary().contains("files=1"));
        assertTrue(diff.summary().contains("+2"));
        assertTrue(diff.summary().contains("-1"));
        assertTrue(diff.summary().contains("hash=0123456789ab"));
    }

    @Test
    void latestDiffObservationReplacesStalePassedEvidence() {
        TaskEvidenceCollector collector = new TaskEvidenceCollector();
        collector.observeDiff(new TaskWorkspaceDiffTracker.DiffSummary(
                true, 1, 1, 0, 0, List.of("src/App.java"), "abcdef"));
        collector.observeDiff(new TaskWorkspaceDiffTracker.DiffSummary(
                false, 0, 0, 0, 0, List.of(), ""));

        List<TaskEvidence> diffs = collector.snapshot().stream()
                .filter(e -> e.type() == EvidenceType.DIFF)
                .toList();
        assertEquals(1, diffs.size());
        assertEquals(EvidenceStatus.FAILED, diffs.get(0).status());
        assertTrue(diffs.get(0).summary().contains("no workspace changes"));
    }

    @Test
    void diffEvidenceBoundsRelatedPathsAndReportsOmittedCount() {
        TaskEvidenceCollector collector = new TaskEvidenceCollector();
        List<String> paths = java.util.stream.IntStream.range(0, 60)
                .mapToObj(index -> "src/File%02d.java".formatted(index))
                .toList();

        collector.observeDiff(new TaskWorkspaceDiffTracker.DiffSummary(
                true, paths.size(), 60, 0, 0, paths, "abcdef"));

        TaskEvidence diff = collector.snapshot().stream()
                .filter(e -> e.type() == EvidenceType.DIFF)
                .findFirst().orElseThrow();
        assertEquals(50, diff.relatedPaths().size());
        assertTrue(diff.summary().contains("omittedPaths=10"), diff.summary());
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
