package com.codeagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeagent.lsp.LspDiagnosticReport;
import com.codeagent.plan.EvidenceType;
import com.codeagent.tool.ToolRegistry.ToolExecutionResult;
import com.codeagent.tool.ToolRegistry.ToolInvocation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Observes bounded execution facts; it never parses assistant prose as evidence. */
public final class TaskEvidenceCollector {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TEST_COMMAND = Pattern.compile(
            "(?i)(^|[;&|\\s])(mvn|gradle|gradlew|npm|pnpm|yarn|pytest|cargo|go)\\s+[^;&|]*\\b(test|check)\\b");
    private static final Pattern BUILD_COMMAND = Pattern.compile(
            "(?i)(^|[;&|\\s])(mvn|gradle|gradlew|npm|pnpm|yarn|cargo)\\s+[^;&|]*(package|verify|compile|build|assemble|install)\\b");

    private final List<TaskEvidence> evidence = new ArrayList<>();

    public void observeTools(List<ToolInvocation> invocations,
                             List<ToolExecutionResult> results,
                             Path projectRoot) {
        if (invocations == null || results == null) {
            return;
        }
        int count = Math.min(invocations.size(), results.size());
        for (int i = 0; i < count; i++) {
            ToolInvocation invocation = invocations.get(i);
            ToolExecutionResult result = results.get(i);
            if (invocation == null || result == null) {
                continue;
            }
            EvidenceStatus status = result.successful() ? EvidenceStatus.PASSED : EvidenceStatus.FAILED;
            if ("write_file".equals(invocation.name())) {
                String path = jsonText(invocation.argumentsJson(), "path");
                evidence.add(new TaskEvidence(EvidenceType.DIFF, status,
                        "write_file " + (status == EvidenceStatus.PASSED ? "completed" : "failed"),
                        path.isBlank() ? List.of() : List.of(path), "write_file", System.currentTimeMillis()));
            }
            if ("execute_command".equals(invocation.name())) {
                String command = jsonText(invocation.argumentsJson(), "command");
                EvidenceType type = TEST_COMMAND.matcher(command).find() ? EvidenceType.TEST
                        : BUILD_COMMAND.matcher(command).find() ? EvidenceType.BUILD : EvidenceType.TOOL_RESULT;
                evidence.add(new TaskEvidence(type, status, summarizeCommand(command), List.of(),
                        "execute_command", System.currentTimeMillis()));
            } else {
                evidence.add(new TaskEvidence(EvidenceType.TOOL_RESULT, status,
                        invocation.name() + " completed", List.of(), invocation.name(), System.currentTimeMillis()));
            }
        }
    }

    public void observeLsp(LspDiagnosticReport report) {
        if (report == null || report.isEmpty()) {
            return;
        }
        EvidenceStatus status = report.errorCount() == 0 ? EvidenceStatus.PASSED : EvidenceStatus.FAILED;
        evidence.add(new TaskEvidence(EvidenceType.LSP, status,
                "errors=" + report.errorCount() + ", warnings=" + report.warningCount(),
                List.of(), "lsp", System.currentTimeMillis()));
    }

    public List<TaskEvidence> snapshot() {
        return List.copyOf(evidence);
    }

    private static String summarizeCommand(String command) {
        String value = command == null ? "" : command.trim();
        return value.length() <= 160 ? value : value.substring(0, 160) + "...";
    }

    private static String jsonText(String json, String field) {
        try {
            JsonNode root = MAPPER.readTree(json == null ? "{}" : json);
            return root.path(field).asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
