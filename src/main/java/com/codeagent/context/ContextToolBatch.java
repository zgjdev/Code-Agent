package com.codeagent.context;

import com.codeagent.llm.LlmClient.ToolCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A contiguous assistant-call and result group, or a protected malformed tool span. */
public record ContextToolBatch(List<ContextNode> nodes, boolean complete) {
    public ContextToolBatch {
        nodes = List.copyOf(nodes);
    }

    public static List<ContextToolBatch> scan(List<ContextNode> sources) {
        List<ContextToolBatch> batches = new ArrayList<>();
        for (int index = 0; index < sources.size();) {
            ContextNode first = sources.get(index);
            List<ToolCall> calls = first.message().toolCalls();
            boolean hasCalls = calls != null && !calls.isEmpty();
            boolean hasToolRole = isTool(first);
            if (!hasCalls && !hasToolRole) {
                index++;
                continue;
            }

            int end = index + 1;
            while (end < sources.size() && isTool(sources.get(end))) end++;
            List<ContextNode> nodes = sources.subList(index, end);
            boolean complete = hasCalls && "assistant".equals(first.message().role())
                    && hasMatchingResults(first, calls, nodes.subList(1, nodes.size()));
            batches.add(new ContextToolBatch(nodes, complete));
            index = end;
        }
        return List.copyOf(batches);
    }

    private static boolean isTool(ContextNode node) {
        return "tool".equals(node.message().role());
    }

    private static boolean hasMatchingResults(ContextNode first, List<ToolCall> calls, List<ContextNode> results) {
        Set<String> pending = new HashSet<>();
        for (ToolCall call : calls) {
            if (call == null || call.id() == null || call.id().isBlank() || call.function() == null
                    || !pending.add(call.id())) return false;
        }
        for (ContextNode result : results) {
            if (!Objects.equals(first.executionId(), result.executionId())
                    || !Objects.equals(first.taskAttemptId(), result.taskAttemptId())
                    || !Objects.equals(first.toolBatchId(), result.toolBatchId())
                    || result.message().toolCalls() != null && !result.message().toolCalls().isEmpty()
                    || !pending.remove(result.message().toolCallId())) return false;
        }
        return pending.isEmpty();
    }
}
