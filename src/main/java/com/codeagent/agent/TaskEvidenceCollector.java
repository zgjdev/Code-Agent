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
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;

/** Observes bounded execution facts; it never parses assistant prose as evidence. */
public final class TaskEvidenceCollector {
    private static final int MAX_RELATED_DIFF_PATHS = 50;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SKIP_TESTS = Pattern.compile(
            "(?i)(?:^|\\s)(?:-D(?:skipTests|maven\\.test\\.skip)(?:=(?!false(?:\\s|$))[^\\s]+)?(?=\\s|$)|--skip-tests|-x\\s+test)(?:\\s|$)");
    private static final Set<String> NON_EXECUTING_FLAGS = Set.of("--version", "--help", "-h", "-?",
            "--dry-run", "--collect-only", "--collectonly", "--co", "--no-run", "--ignore-scripts", "--setup-only",
            "--list-tests", "--listtests", "--list", "-list", "--fail-never", "-fn");
    private static final Set<String> OPTION_VALUES = Set.of("-f", "--file", "-pl", "--projects", "-P",
            "--activate-profiles", "-s", "--settings", "-gs", "--global-settings", "-t", "--toolchains",
            "-T", "--threads", "-D", "--define", "-rf", "--resume-from", "-p", "--project-dir",
            "-g", "--gradle-user-home", "-b", "--build-file", "-c", "--settings-file", "--tests",
            "-I", "--init-script", "-x", "--exclude-task", "--project-cache-dir", "--include-build",
            "--max-workers", "--console", "--warning-mode");
    private static final Set<String> BUILD_FLAGS = Set.of("-B", "--batch-mode", "-o", "--offline", "-q",
            "--quiet", "-e", "--errors", "-X", "--debug", "-U", "--update-snapshots", "-N",
            "--non-recursive", "-am", "--also-make", "-amd", "--also-make-dependents", "-fae",
            "--fail-at-end", "-ff", "--fail-fast", "-V", "--show-version", "--no-daemon", "--daemon",
            "--console=plain", "--stacktrace", "--full-stacktrace", "--info", "--warn", "--continue",
            "--parallel", "--no-parallel", "--rerun-tasks", "--no-build-cache", "--build-cache");

    private final List<TaskEvidence> evidence = new ArrayList<>();
    private final Map<String, TaskEvidence> latestCommands = new HashMap<>();

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
            EvidenceStatus status = result.observationUnknown() ? EvidenceStatus.MISSING
                    : result.successful() ? EvidenceStatus.PASSED : EvidenceStatus.FAILED;
            if ("execute_command".equals(invocation.name())) {
                String command = result.executedCommand() != null ? result.executedCommand()
                        : jsonText(invocation.argumentsJson(), "command");
                CommandClassification classification = classifyCommand(command);
                EvidenceType type = classification.type();
                if (status == EvidenceStatus.PASSED && classification.nonExecuting()) status = EvidenceStatus.MISSING;
                String output = result.result() == null ? "" : result.result();
                String summary = summarizeCommand(command) + "\noutput: "
                        + (output.length() <= 1000 ? output : output.substring(0, 1000) + " [truncated]");
                TaskEvidence item = new TaskEvidence(type, status, summary, List.of(),
                        "execute_command", System.currentTimeMillis());
                if (type == EvidenceType.TEST || type == EvidenceType.BUILD) {
                    TaskEvidence previous = latestCommands.put(command, item);
                    if (previous != null) evidence.remove(previous);
                }
                evidence.add(item);
            } else {
                evidence.add(new TaskEvidence(EvidenceType.TOOL_RESULT, status,
                        invocation.name() + " completed", List.of(), invocation.name(), System.currentTimeMillis()));
            }
        }
    }

    public void observeDiff(TaskWorkspaceDiffTracker.DiffSummary summary) {
        evidence.removeIf(item -> item.type() == EvidenceType.DIFF);
        if (summary == null) {
            return;
        }
        EvidenceStatus status = summary.changed() ? EvidenceStatus.PASSED : EvidenceStatus.FAILED;
        String digest = summary.digest().isBlank()
                ? ""
                : summary.digest().substring(0, Math.min(12, summary.digest().length()));
        List<String> relatedPaths = summary.changedPaths().stream()
                .limit(MAX_RELATED_DIFF_PATHS)
                .toList();
        int omittedPaths = Math.max(0, summary.changedPaths().size() - relatedPaths.size());
        String detail = summary.changed()
                ? "files=" + summary.changedFiles()
                + ", +" + summary.additions()
                + ", -" + summary.deletions()
                + (summary.lineStatsUnavailableFiles() > 0
                ? ", lineStatsUnavailable=" + summary.lineStatsUnavailableFiles() : "")
                + (omittedPaths > 0 ? ", omittedPaths=" + omittedPaths : "")
                + (digest.isBlank() ? "" : ", hash=" + digest)
                : "no workspace changes detected";
        evidence.add(new TaskEvidence(EvidenceType.DIFF, status, detail,
                relatedPaths, "workspace_diff", System.currentTimeMillis()));
    }

    public void observeDiffFailure(String reason) {
        evidence.removeIf(item -> item.type() == EvidenceType.DIFF);
        String detail = reason == null || reason.isBlank()
                ? "workspace diff unavailable"
                : "workspace diff unavailable: " + summarizeCommand(reason);
        evidence.add(new TaskEvidence(EvidenceType.DIFF, EvidenceStatus.FAILED, detail,
                List.of(), "workspace_diff", System.currentTimeMillis()));
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

    private record CommandClassification(EvidenceType type, boolean nonExecuting) { }

    /** Conservative command recognition, never a shell interpreter or proof of test coverage. */
    private static CommandClassification classifyCommand(String command) {
        List<String> tokens = literalTokens(command);
        if (tokens.isEmpty()) return new CommandClassification(EvidenceType.TOOL_RESULT, false);
        String program = tokens.get(0).replace('\\', '/').replaceFirst("^\\./", "")
                .replaceFirst("(?i)\\.(cmd|bat|exe)$", "").toLowerCase(Locale.ROOT);
        List<String> args = tokens.subList(1, tokens.size());
        if ((program.equals("python") || program.equals("python3")) && args.size() >= 2
                && args.get(0).equals("-m") && args.get(1).equals("pytest")) {
            program = "pytest";
            args = args.subList(2, args.size());
        }
        boolean nonExecuting = false;
        for (String argument : args) {
            String flag = argument.toLowerCase(Locale.ROOT);
            String key = flag.split("=", 2)[0];
            if ((NON_EXECUTING_FLAGS.contains(key) && !flag.endsWith("=false"))
                    || (flag.equals("-v") && Set.of("mvn", "mvnw", "gradle", "gradlew", "npm", "pnpm", "yarn").contains(program)
                        && !(program.startsWith("mvn") && argument.equals("-V")))
                    || (program.equals("cargo") && argument.equals("-V"))
                    || (program.startsWith("gradle") && flag.equals("-m"))) nonExecuting = true;
        }
        EvidenceType type = EvidenceType.TOOL_RESULT;
        if (program.equals("pytest")) {
            type = EvidenceType.TEST;
        } else if (program.equals("mvn") || program.equals("mvnw")
                || program.equals("gradle") || program.equals("gradlew")) {
            List<String> goals = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                if (OPTION_VALUES.contains(arg)) { i++; continue; }
                if (arg.startsWith("-") && !BUILD_FLAGS.contains(arg) && !NON_EXECUTING_FLAGS.contains(arg.toLowerCase(Locale.ROOT))
                        && !arg.startsWith("-D") && !arg.startsWith("-P") && !arg.contains("=")) {
                    return new CommandClassification(EvidenceType.TOOL_RESULT, false);
                }
                if (!arg.startsWith("-") && !arg.contains("=")) goals.add(arg);
            }
            boolean test = goals.stream().anyMatch(goal -> goal.equals("test") || goal.equals("surefire:test")
                    || (programIsGradle(tokens.get(0)) && (goal.equals("check") || goal.endsWith(":test"))));
            boolean build = goals.stream().anyMatch(goal -> Set.of("package", "verify", "compile", "build",
                    "assemble", "install", "classes", "compileJava").contains(goal));
            type = test ? EvidenceType.TEST : build ? EvidenceType.BUILD : EvidenceType.TOOL_RESULT;
        } else if (Set.of("npm", "pnpm", "yarn").contains(program)) {
            int index = packageSubcommand(args);
            if (index >= 0) {
                String goal = args.get(index);
                if ((goal.equals("run") || goal.equals("run-script")) && index + 1 < args.size()) goal = args.get(index + 1);
                if (goal.equals("test") || goal.equals("t") || goal.startsWith("test:")) type = EvidenceType.TEST;
                else if (Set.of("build", "compile", "install").contains(goal)) type = EvidenceType.BUILD;
            }
        } else if (program.equals("cargo") || program.equals("go")) {
            if (!args.isEmpty() && args.get(0).equals("test")) type = EvidenceType.TEST;
            else if (!args.isEmpty() && Set.of("build", "check", "install").contains(args.get(0))) type = EvidenceType.BUILD;
        }
        if (type == EvidenceType.TEST && SKIP_TESTS.matcher(String.join(" ", args)).find()) nonExecuting = true;
        if (type == EvidenceType.TEST && program.startsWith("gradle")
                && args.stream().anyMatch(arg -> arg.equals("-x") || arg.startsWith("--exclude-task"))) nonExecuting = true;
        return new CommandClassification(type, type != EvidenceType.TOOL_RESULT && nonExecuting);
    }

    private static boolean programIsGradle(String program) {
        return program.replace('\\', '/').replaceFirst("^\\./", "").startsWith("gradle");
    }

    private static int packageSubcommand(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (!arg.startsWith("-")) return i;
            if (Set.of("--prefix", "--workspace", "-w", "--dir", "-C", "--filter", "-F",
                    "--userconfig", "--cache", "--registry").contains(arg)) { i++; continue; }
            if (!arg.contains("=") && !Set.of("--silent", "--quiet", "--offline", "--version", "--help",
                    "-v", "-h", "--ignore-scripts", "--dry-run").contains(arg)) return -1;
        }
        return -1;
    }

    private static List<String> literalTokens(String command) {
        if (command == null || command.matches("(?s).*[;&|<>`$()\\r\\n].*")) return List.of();
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (char character : command.toCharArray()) {
            if (quote != 0) {
                if (character == quote) quote = 0; else token.append(character);
            } else if (character == '\'' || character == '"') {
                quote = character;
                started = true;
            } else if (Character.isWhitespace(character)) {
                if (started) { tokens.add(token.toString()); token.setLength(0); started = false; }
            } else { token.append(character); started = true; }
        }
        if (quote != 0) return List.of();
        if (started) tokens.add(token.toString());
        return tokens;
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
