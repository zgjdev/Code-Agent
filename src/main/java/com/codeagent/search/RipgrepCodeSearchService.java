package com.codeagent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class RipgrepCodeSearchService implements CodeSearchService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private final Set<String> excludedDirs;
    private final JavaCodeSearchService fallback;

    public RipgrepCodeSearchService(Set<String> excludedDirs) {
        this.excludedDirs = Set.copyOf(excludedDirs);
        this.fallback = new JavaCodeSearchService(excludedDirs);
    }

    @Override
    public CodeSearchResult search(CodeSearchRequest request) {
        if (Boolean.getBoolean("codeagent.search.disable.rg") || !isRipgrepAvailable()) {
            return fallback.search(request);
        }
        Process process = null;
        ExecutorService reader = null;
        try {
            process = new ProcessBuilder(command(request)).directory(request.projectRoot().toFile())
                    .redirectErrorStream(true).start();
            reader = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "codeagent-rg-reader");
                thread.setDaemon(true);
                return thread;
            });
            Process active = process;
            Future<CodeSearchResult> parsed = reader.submit(() -> parse(active, request));
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                parsed.cancel(true);
                return new CodeSearchResult("rg", List.of(), true, "rg 搜索超时 8 秒");
            }
            return parsed.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return fallback.search(request);
        } finally {
            if (reader != null) reader.shutdownNow();
        }
    }

    private static CodeSearchResult parse(Process process, CodeSearchRequest request) throws IOException {
        List<GrepMatch> matches = new ArrayList<>();
        List<ContextLine> pending = new ArrayList<>();
        MutableMatch current = null;
        Map<String, Integer> perFile = new HashMap<>();
        boolean partial = false;
        String reason = "";
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode event = MAPPER.readTree(line);
                JsonNode data = event.path("data");
                if ("context".equals(event.path("type").asText())) {
                    ContextLine context = context(data);
                    String file = file(data);
                    if (current != null && current.file.equals(file)) current.context.add(context);
                    else pending.add(context);
                } else if ("match".equals(event.path("type").asText())) {
                    if (current != null) matches.add(current.freeze());
                    if (matches.size() >= request.maxResults()) {
                        partial = true;
                        reason = "已达到 max_results=" + request.maxResults();
                        process.destroyForcibly();
                        break;
                    }
                    String file = file(data);
                    int count = perFile.getOrDefault(file, 0);
                    if (count >= request.headLimit()) {
                        partial = true;
                        reason = "部分文件已达到 head_limit=" + request.headLimit();
                        pending.clear();
                        current = null;
                        continue;
                    }
                    current = new MutableMatch(file, data.path("line_number").asInt(), new ArrayList<>(pending));
                    current.context.add(context(data));
                    pending.clear();
                    perFile.put(file, count + 1);
                }
            }
        }
        if (current != null && matches.size() < request.maxResults()) matches.add(current.freeze());
        return new CodeSearchResult("rg", matches, partial, reason);
    }

    private List<String> command(CodeSearchRequest request) {
        List<String> command = new ArrayList<>(List.of("rg", "--json", "--color=never",
                "--line-number", "--max-filesize", "2M"));
        if (!request.caseSensitive()) command.add("-i");
        if (!request.regex()) command.add("--fixed-strings");
        if (request.contextLines() > 0) {
            command.add("-C");
            command.add(String.valueOf(request.contextLines()));
        }
        excludedDirs.stream().sorted().forEach(directory -> {
            command.add("--glob");
            command.add("!" + directory + "/**");
        });
        if (request.glob() != null && !request.glob().isBlank()) {
            command.add("--glob");
            command.add(request.glob());
        }
        command.add("--");
        command.add(request.query());
        command.add(request.root().equals(request.projectRoot())
                ? "." : request.projectRoot().relativize(request.root()).toString());
        return command;
    }

    private static ContextLine context(JsonNode data) {
        return new ContextLine(data.path("line_number").asInt(),
                data.path("lines").path("text").asText().stripTrailing());
    }

    private static String file(JsonNode data) {
        return data.path("path").path("text").asText().replace('\\', '/');
    }

    private static boolean isRipgrepAvailable() {
        try {
            Process process = new ProcessBuilder("rg", "--version").start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            return finished && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final class MutableMatch {
        private final String file;
        private final int line;
        private final List<ContextLine> context;
        private MutableMatch(String file, int line, List<ContextLine> context) {
            this.file = file;
            this.line = line;
            this.context = context;
        }
        private GrepMatch freeze() { return new GrepMatch(file, line, context); }
    }
}
