package com.codeagent.agent;

import com.codeagent.llm.LlmClient;
import com.codeagent.tool.ToolRegistry;
import com.codeagent.render.Renderer;
import com.codeagent.render.StatusInfo;
import com.codeagent.hitl.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Read-only fixture harness. Reports metrics only, never model messages, tool payloads or answers. */
final class CodeSearchAgentHarness {
    static final ObjectMapper JSON = new ObjectMapper();
    static JsonNode fixture() throws IOException {
        try(var input=CodeSearchAgentHarness.class.getResourceAsStream("/rag/code-search-coordination.json")) {
            if(input==null) throw new IOException("Missing search fixture");
            return JSON.readTree(input);
        }
    }
    static void writeFixture(Path root,JsonNode fixture) throws IOException {
        Files.createDirectories(root);
        var fields=fixture.path("files").fields();
        while(fields.hasNext()) {var field=fields.next(); Files.writeString(root.resolve(field.getKey()),field.getValue().asText());}
    }
    static boolean hasEvidence(List<ToolRegistry.ToolExecutionResult> outputs,JsonNode evidence) throws IOException {
        if(evidence.isEmpty()) return outputs.stream().anyMatch(r->r.successful() && Set.of("grep_code","glob_files","search_code").contains(r.name()));
        var files=evidence.fields();
        while(files.hasNext()) {
            var file=files.next();
            for(var required:file.getValue()) {
                boolean found=false;
                for(var result:outputs) if(result.successful() && result.name().equals("read_file")) {
                    String path=JSON.readTree(result.argumentsJson()).path("path").asText();
                    if(Path.of(path).getFileName().toString().equals(file.getKey()) && result.result().contains(required.asText())) found=true;
                }
                if(!found)return false;
            }
        }
        return true;
    }
    static boolean matchesFacts(String answer,JsonNode expected) {
        if(answer==null || expected.isEmpty())return false;
        var objects=Pattern.compile("\\{[^{}]*}").matcher(answer);
        String last=null; int end=0;
        while(objects.find()) {
            last=objects.group(); end=objects.end();
        }
        if(last==null || !answer.substring(end).strip().matches("(?:```)?"))return false;
        try {
            var actual=JSON.readTree(last);
            var fields=expected.fields();
            while(fields.hasNext()) {var field=fields.next();if(!field.getValue().equals(actual.path(field.getKey())))return false;}
            return true;
        } catch(IOException invalid) { return false; }
    }

    static final class RecordingRegistry extends ToolRegistry {
        static final Set<String> ALLOWED=Set.of("grep_code","glob_files","read_file","search_code","list_dir");
        final List<ToolExecutionResult> outputs=new ArrayList<>();
        @Override public List<LlmClient.Tool> getToolDefinitions() {
            return super.getToolDefinitions().stream().filter(t->ALLOWED.contains(t.name())).toList();
        }
        @Override public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
            if(invocations.stream().anyMatch(i->!ALLOWED.contains(i.name())))
                throw new IllegalArgumentException("Evaluation permits read-only search tools only");
            var result=super.executeTools(invocations); outputs.addAll(result); return result;
        }
        Set<String> readFiles() throws IOException {
            Set<String> files=new HashSet<>();
            for(var result:outputs) if(result.name().equals("read_file") && result.successful()) {
                String file=JSON.readTree(result.argumentsJson()).path("path").asText().replace('\\','/');
                files.add(Path.of(file).getFileName().toString());
            }
            return files;
        }
    }
    static final class CountingClient implements LlmClient {
        private final LlmClient delegate;
        int requests; long inputTokens,outputTokens;
        CountingClient(LlmClient delegate) {this.delegate=delegate;}
        @Override public ChatResponse chat(List<Message> messages,List<Tool> tools) throws IOException {
            return chat(messages,tools,StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages,List<Tool> tools,StreamListener listener) throws IOException {
            if(messages.stream().filter(m->m.role().equals("system")).anyMatch(m->m.content().contains("CODEAGENT.md 项目记忆")))
                throw new IOException("Evaluation isolation failure: project memory entered the request");
            if(++requests>12) throw new IOException("Evaluation request limit exceeded");
            var response=delegate.chat(messages,tools,listener);
            inputTokens+=Math.max(0,response.inputTokens()); outputTokens+=Math.max(0,response.outputTokens());
            return response;
        }
        @Override public String getModelName() {return delegate.getModelName();}
        @Override public String getProviderName() {return delegate.getProviderName();}
        @Override public int maxContextWindow() {return delegate.maxContextWindow();}
        @Override public boolean supportsTools() {return delegate.supportsTools();}
    }
    static final class SilentRenderer implements Renderer {
        private final PrintStream sink=new PrintStream(OutputStream.nullOutputStream());
        public void start() {} public void close() {sink.close();} public PrintStream stream() {return sink;}
        public void appendToolCalls(List<LlmClient.ToolCall> calls) {}
        public void appendDiff(String file,String before,String after) {}
        public void updateStatus(StatusInfo status) {}
        public ApprovalResult promptApproval(ApprovalRequest request) {return ApprovalResult.reject("Read-only evaluation");}
        public int openPalette(String title,List<String> items) {return -1;}
    }
}
