package com.codeagent.memory;

import com.codeagent.llm.LlmClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class MemoryTestLlmClient implements LlmClient {
    private final String responseContent;
    private final AtomicInteger calls = new AtomicInteger();

    MemoryTestLlmClient(String responseContent) {
        this.responseContent = responseContent;
    }

    int calls() {
        return calls.get();
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        calls.incrementAndGet();
        return new ChatResponse("assistant", responseContent, null, List.of(), 0, 0, 0);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools,
                             StreamListener listener) throws IOException {
        return chat(messages, tools);
    }

    @Override public String getModelName() { return "memory-test"; }
    @Override public String getProviderName() { return "memory-test"; }
}
