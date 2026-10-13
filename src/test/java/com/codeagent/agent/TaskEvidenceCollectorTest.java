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
    void approvalModifiedEchoCannotEraseFailedOriginalTest(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) {
        var approvals = new java.util.concurrent.atomic.AtomicInteger();
        var handler = new com.codeagent.hitl.HitlHandler() {
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
            public com.codeagent.hitl.ApprovalResult requestApproval(com.codeagent.hitl.ApprovalRequest request) {
                return approvals.getAndIncrement() == 0 ? com.codeagent.hitl.ApprovalResult.approve()
                        : com.codeagent.hitl.ApprovalResult.modify("{\"command\":\"echo approval-modified\"}");
            }
        };
        var registry = new com.codeagent.hitl.HitlToolRegistry(handler);
        registry.setProjectPath(temp.toString());
        var calls = List.of(new ToolInvocation("test", "execute_command", "{\"command\":\"mvn test -o\"}"));
        var collector = new TaskEvidenceCollector();
        collector.observeTools(calls, registry.executeTools(calls), temp);
        var modifiedResults = registry.executeTools(calls);
        assertEquals("echo approval-modified", modifiedResults.get(0).executedCommand());
        assertEquals(calls.get(0).argumentsJson(), modifiedResults.get(0).argumentsJson(), "original call identity stays immutable");
        collector.observeTools(calls, modifiedResults, temp);
        assertEquals(1, collector.snapshot().stream().filter(e -> e.type() == EvidenceType.TEST
                && e.status() == EvidenceStatus.FAILED).count());
        assertEquals(0, collector.snapshot().stream().filter(e -> e.type() == EvidenceType.TEST
                && e.status() == EvidenceStatus.PASSED).count());
        assertTrue(collector.snapshot().stream().anyMatch(e -> e.summary().contains("echo approval-modified")));
    }

    @Test
    void nonExecutingModesAndNonTestSubcommandsCannotPassTestEvidence() {
        var collector = new TaskEvidenceCollector();
        for (String command : List.of("npm --version test", "npm run lint -- test", "pytest --collect-only",
                "gradle test --dry-run", "npm test --ignore-scripts", "cargo test --no-run", "go test -list .",
                "mvn test --fail-never", "mvn -P test compile", "gradle --project-cache-dir test compileJava",
                "gradle :module:test -x :module:test", "pytest --setup-only")) {
            observeCommand(collector, command, true, "no tests executed");
        }
        assertEquals(0, collector.snapshot().stream().filter(e -> e.type() == EvidenceType.TEST
                && e.status() == EvidenceStatus.PASSED).count());
    }

    @Test
    void verboseTestsAndKnownTestScriptsRemainRecognizable() {
        var collector = new TaskEvidenceCollector();
        for (String command : List.of("pytest -v", "go test -v ./...", "cargo test -v",
                "mvn -V -P profile test -DskipTests=false", "npm run test:unit", "gradle :module:test --no-daemon")) {
            observeCommand(collector, command, true, "executed");
        }
        assertEquals(6, collector.snapshot().stream().filter(e -> e.type() == EvidenceType.TEST
                && e.status() == EvidenceStatus.PASSED).count());
    }
    @Test
    void realFailingMavenCommandCannotSatisfyTestGate(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) {
        // No pom.xml: offline Maven must fail, without network or project side effects.
        var registry = new com.codeagent.tool.ToolRegistry();
        registry.setProjectPath(temp.toString());
        var calls = List.of(new ToolInvocation("test", "execute_command", "{\"command\":\"mvn test -o\"}"));
        var results = registry.executeTools(calls);
        assertTrue(!results.get(0).successful(), results.get(0).result());
        TaskEvidenceCollector collector = new TaskEvidenceCollector();
        collector.observeTools(calls, results, temp);
        assertEquals(EvidenceStatus.FAILED, collector.snapshot().get(0).status());
        assertEquals(EvidenceType.TEST, collector.snapshot().get(0).type());
        var task = new com.codeagent.plan.Task("verify", "tests", com.codeagent.plan.Task.TaskType.VERIFICATION,
                List.of(), null, List.of(), java.util.Set.of(EvidenceType.TEST));
        assertEquals(VerificationOutcome.REJECTED, DeterministicEvidenceGate.evaluate(task, collector.snapshot()).outcome());
    }
    @Test
    void latestSameCommandReplacesFailureButDifferentFailureRemains() {
        TaskEvidenceCollector collector = new TaskEvidenceCollector();
        observeCommand(collector, "mvn test -Dtest=A", false, "exit code: 7\nassertion failed");
        observeCommand(collector, "mvn test -Dtest=B", true, "exit code: 0\nTests run: 2");
        assertEquals(2, collector.snapshot().size());
        assertEquals(1, collector.snapshot().stream().filter(e -> e.status() == EvidenceStatus.FAILED).count());
        assertTrue(collector.snapshot().get(0).summary().contains("assertion failed"));
        observeCommand(collector, "mvn test -Dtest=A", true, "exit code: 0\nTests run: 3");
        assertEquals(2, collector.snapshot().size());
        assertEquals(0, collector.snapshot().stream().filter(e -> e.status() == EvidenceStatus.FAILED).count());
    }

    @Test
    void echoAndSkippedTestsAreNotPassedTestEvidence() {
        TaskEvidenceCollector collector = new TaskEvidenceCollector();
        observeCommand(collector, "echo mvn test", true, "mvn test");
        observeCommand(collector, "mvn test -DskipTests=true", true, "Tests skipped");
        assertEquals(0, collector.snapshot().stream().filter(e -> e.type() == EvidenceType.TEST
                && e.status() == EvidenceStatus.PASSED).count());
    }

    @Test
    void truncatedCommandSummariesCannotMergeDistinctCommands() {
        TaskEvidenceCollector collector = new TaskEvidenceCollector();
        String prefix = "mvn test -Dvalue=" + "x".repeat(200);
        observeCommand(collector, prefix + "A", false, "failure");
        observeCommand(collector, prefix + "B", true, "success");
        assertEquals(2, collector.snapshot().size());
        assertEquals(EvidenceStatus.FAILED, collector.snapshot().get(0).status());
    }

    private static void observeCommand(TaskEvidenceCollector collector, String command, boolean success, String output) {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("command", command).toString();
        collector.observeTools(List.of(new ToolInvocation("c", "execute_command", json)),
                List.of(result("c", "execute_command", success, output)), null);
    }
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
