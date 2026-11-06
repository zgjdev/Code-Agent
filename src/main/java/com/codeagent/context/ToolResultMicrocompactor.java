package com.codeagent.context;

import com.codeagent.llm.LlmClient.Message;
import com.codeagent.memory.MemoryEntry;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static com.codeagent.context.DeterministicContextEdits.*;

/** Replaces an old result body with a bounded historical record, leaving its invocation intact. */
public final class ToolResultMicrocompactor {
    private static final int MAX_RECORD_TOKENS = 768;
    private static final Set<String> WEB_TOOLS = Set.of("web_search", "web_fetch");

    public Optional<ContextEdit> propose(ContextWindowState.Snapshot snapshot, ContextWindowState.Options options) {
        var unavailable = new HashSet<NodeId>();
        for (ContextEdit edit : snapshot.activeEdits()) {
            unavailable.addAll(edit.orderedSourceIds());
            edit.dependencies().stream().filter(ContextEdit.Dependency::requiresFullBody)
                    .forEach(dependency -> unavailable.add(dependency.nodeId()));
        }
        for (ContextToolBatch batch : ContextToolBatch.scan(snapshot.sourceNodes())) {
            if (!batch.complete() || batch.nodes().stream().anyMatch(n -> protectedNode(snapshot, n))) continue;
            var callMessage = batch.nodes().get(0).message();
            for (ContextNode result : batch.nodes().subList(1, batch.nodes().size())) {
                if (unavailable.contains(result.id()) || !plain(result)) continue;
                var call = callMessage.toolCalls().stream().filter(c -> c.id().equals(result.message().toolCallId()))
                        .findFirst().orElseThrow();
                String tool = call.function().name();
                var args = arguments(call.function().arguments());
                String body = result.message().content();
                if (tool == null || args == null || body == null || !success(result, tool)
                        || MemoryEntry.estimateTokens(body) < options.minMicroResultTokens()) continue;
                boolean command = "execute_command".equals(tool);
                if (!command && !READ_TOOLS.contains(tool) && !WEB_TOOLS.contains(tool)) continue;
                var descriptor = result.descriptor();
                if (command && (!Integer.valueOf(0).equals(descriptor.exitCode())
                        || descriptor.executedCommand() == null || descriptor.executedCommand().isBlank())) continue;

                var facts = new TreeMap<String, Object>();
                facts.put("tool", tool);
                facts.put("arguments", command ? null : args);
                facts.put("resourceLocation", new TreeMap<>(descriptor.resourceLocation()));
                facts.put("status", descriptor.processOutcome());
                facts.put("captureCompleteness", descriptor.captureCompleteness());
                facts.put("resultCount", descriptor.resultCount() == null ? "unknown" : descriptor.resultCount());
                facts.put("capturedCodePoints", body.codePointCount(0, body.length()));
                facts.put("capturedContentSha256", ContextDigests.textDigest(body));
                if (command) {
                    facts.put("executedCommand", descriptor.executedCommand());
                    facts.put("exitCode", descriptor.exitCode());
                    facts.put("elapsedMillis", descriptor.elapsedMillis());
                }
                if (WEB_TOOLS.contains(tool)) facts.put("source", "historical_web_result");
                String shortened = shortRecord(facts, body, command ? 512 : 256);
                if (shortened == null) continue;
                String editId = editId(snapshot, ContextEdit.Layer.MICRO, result);
                var replacement = new ContextNode(NodeId.edit(snapshot.scopeId(), editId, 0),
                        Message.tool(result.message().toolCallId(), shortened), result.sourceKind(),
                        result.executionId(), result.taskAttemptId(), result.toolBatchId(), descriptor);
                List<ContextNode> source = List.of(result);
                List<ContextNode> replacements = List.of(replacement);
                if (tokens(source) - tokens(replacements) < options.minSavingsTokens()) continue;
                return Optional.of(new ContextEdit(editId, ContextEdit.Layer.MICRO, ContextEdit.Kind.REPLACE_BODY,
                        ids(source), ContextDigests.sourceDigest(source), replacements, List.of(), List.of(), 1));
            }
        }
        return Optional.empty();
    }

    private static String shortRecord(Map<String, Object> facts, String body, int maxExcerpt) {
        int length = body.codePointCount(0, body.length());
        for (int size = Math.min(maxExcerpt, (length - 1) / 2); size >= 0; size = size == 0 ? -1 : size / 2) {
            facts.put("capturedHead", body.substring(0, body.offsetByCodePoints(0, size)));
            facts.put("capturedTail", body.substring(body.offsetByCodePoints(body.length(), -size)));
            String shortened;
            try {
                shortened = "[历史工具正文已缩减，捕获文本的中间部分已省略] " + JSON.writeValueAsString(facts);
            } catch (JsonProcessingException e) {
                return null;
            }
            if (MemoryEntry.estimateTokens(shortened) <= MAX_RECORD_TOKENS) return shortened;
        }
        return null;
    }
}
