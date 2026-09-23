package com.codeagent.cli;

import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;
import com.codeagent.mcp.resources.McpResourceDescriptor;
import com.codeagent.skill.Skill;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAgentCompleterTest {

    @Test
    void suggestsSlashCommandsWhenInputStartsWithSlash() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/", "/"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/model")));
        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/react")));
        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/browser connect")));
        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/search <查询>")));
    }

    @Test
    void completesSubCommandWithoutDuplicatingPrefix() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/mcp r", "r"), candidates);

        Candidate restart = candidates.stream()
                .filter(c -> c.displ().equals("/mcp restart <name>"))
                .findFirst()
                .orElseThrow();
        assertEquals("restart ", restart.value());
    }

    @Test
    void ignoresNormalWords() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("hello", "hello"), candidates);

        assertTrue(candidates.isEmpty());
    }

    @Test
    void completesModelProviderNames() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/model st", "st"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("step")));
    }

    @Test
    void completesHunyuanModelNames() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> providerCandidates = new ArrayList<>();
        List<Candidate> modelCandidates = new ArrayList<>();

        completer.complete(null, parsed("/model hun", "hun"), providerCandidates);
        completer.complete(null, parsed("/model hy", "hy"), modelCandidates);

        assertTrue(providerCandidates.stream().anyMatch(c -> c.value().equals("hunyuan")));
        assertTrue(modelCandidates.stream().anyMatch(c -> c.value().equals("hy4-preview")));
    }

    @Test
    void completesConfigProviderCommand() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/config provider fr", "fr"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("freellmapi ")));
    }

    @Test
    void completesAgnesProviderCommand() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/config provider ag", "ag"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("agnes ")));
    }

    @Test
    void completesXfyunProviderCommand() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/config provider xf", "xf"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("xfyun ")));
    }

    @Test
    void completesHunyuanProviderCommand() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/config provider hu", "hu"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("hunyuan ")));
    }

    @Test
    void completesMcpServerNamesFromResources() {
        CodeAgentCompleter completer = new CodeAgentCompleter(() -> List.of(
                new McpResourceDescriptor("chrome-devtools", "file:///a", "a", "", "", "text/plain", null),
                new McpResourceDescriptor("filesystem", "file:///b", "b", "", "", "text/plain", null)
        ));
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/mcp logs ch", "ch"), candidates);

        Candidate candidate = candidates.stream()
                .filter(c -> c.value().equals("chrome-devtools"))
                .findFirst()
                .orElseThrow();
        assertEquals("MCP server", candidate.group());
    }

    @Test
    void completesSkillNames() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of, () -> List.of(
                skill("web-access", "浏览器和联网策略"),
                skill("ai-article", "文章写作")
        ));
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/skill show web", "web"), candidates);

        Candidate candidate = candidates.stream()
                .filter(c -> c.value().equals("web-access"))
                .findFirst()
                .orElseThrow();
        assertEquals("浏览器和联网策略", candidate.descr());
    }

    @Test
    void completesSkillSubCommands() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/skill sh", "sh"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("show ")));
    }

    @Test
    void completesTaskSubCommands() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/task ca", "ca"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("cancel ")));
    }

    @Test
    void completesPlanRecoveryCommandsFromSlashHints() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/plan r", "r"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/plan resume")));
    }

    @Test
    void completesSessionIdsForResume() {
        CodeAgentCompleter completer = new CodeAgentCompleter(
                List::of, List::of, () -> List.of("session-123", "session-456"));
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/resume session-1", "session-1"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("session-123")));
    }

    @Test
    void completesLocalPathMentions() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("@pom", "@pom"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("@pom.xml")));
    }

    @Test
    void completesImagePathMentionsWithTokenPrefix() {
        CodeAgentCompleter completer = new CodeAgentCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("@image:pom", "@image:pom"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("@image:pom.xml")));
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "1.0.0", null, List.of(), Skill.Source.USER, "body", null, null);
    }

    private static ParsedLine parsed(String line, String word) {
        return new ParsedLine() {
            @Override public String word() { return word; }
            @Override public int wordCursor() { return word.length(); }
            @Override public int wordIndex() { return 0; }
            @Override public List<String> words() { return List.of(word); }
            @Override public String line() { return line; }
            @Override public int cursor() { return line.length(); }
        };
    }
}
