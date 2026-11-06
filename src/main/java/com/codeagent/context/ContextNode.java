package com.codeagent.context;

import com.codeagent.llm.LlmClient.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record ContextNode(NodeId id, Message message, SourceKind sourceKind, String executionId,
                          String taskAttemptId, String toolBatchId, ToolResultDescriptor descriptor,
                          SyntheticContext syntheticContext) {
    public record SyntheticContext(String contextKey, long generation, boolean completeReplacement) {
        public SyntheticContext {
            if (contextKey == null || contextKey.isBlank() || generation < 0) {
                throw new IllegalArgumentException("Invalid synthetic context identity");
            }
        }
    }

    public ContextNode(NodeId id, Message message, SourceKind sourceKind, String executionId,
                       String taskAttemptId, String toolBatchId, ToolResultDescriptor descriptor) {
        this(id, message, sourceKind, executionId, taskAttemptId, toolBatchId, descriptor, null);
    }

    public ContextNode {
        Objects.requireNonNull(id);
        Objects.requireNonNull(message);
        Objects.requireNonNull(sourceKind);
        message = new Message(message.role(), message.content(), message.reasoningContent(),
                freeze(message.toolCalls()), message.toolCallId(), freeze(message.contentParts()));
        if (syntheticContext != null && sourceKind != SourceKind.SYNTHETIC) {
            throw new IllegalArgumentException("Synthetic metadata requires synthetic provenance");
        }
        if (descriptor != null && (!"tool".equals(message.role())
                || !Objects.equals(message.toolCallId(), descriptor.invocationId()))) {
            throw new IllegalArgumentException("Tool descriptor does not match result message");
        }
    }

    private static <T> List<T> freeze(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
