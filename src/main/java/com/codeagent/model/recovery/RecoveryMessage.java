package com.codeagent.model.recovery;

import java.util.List;

public record RecoveryMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {

    public RecoveryMessage {
        if (!List.of("assistant", "tool").contains(role))
            throw new IllegalArgumentException("Unsupported recovery role");
        content = RecoveryBounds.exact(content, 32768);
        toolCalls = RecoveryBounds.copy(toolCalls, 64);
    }

    public record ToolCall(String id, String name, String arguments) {

        public ToolCall {
            arguments = RecoveryBounds.exact(arguments, 32768);
        }

        @Override
        public String toString() {
            return "RecoveryToolCall";
        }
    }

    @Override
    public String toString() {
        return "RecoveryMessage[role=" + role + ", toolCallCount=" + toolCalls.size() + "]";
    }
}
