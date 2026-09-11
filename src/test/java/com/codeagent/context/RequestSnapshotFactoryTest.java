package com.codeagent.context;

import com.codeagent.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestSnapshotFactoryTest {
    private final LlmClient client = new LlmClient() {
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException { return null; }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException { return null; }
        public String getModelName() { return "model"; }
        public String getProviderName() { return "provider"; }
    };

    @Test
    void capturesCompleteRequestAndStableFingerprints() {
        RequestSnapshotFactory factory = new RequestSnapshotFactory();
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.system("system"), LlmClient.Message.user("hello"));

        RequestSnapshot first = factory.capture(client, messages, List.of(), 7);
        RequestSnapshot second = factory.capture(client, List.copyOf(messages), List.of(), 8);

        assertEquals(first.surfaceFingerprint(), second.surfaceFingerprint());
        assertEquals(first.estimatedRequestTokens(),
                (long) first.estimatedSurfaceTokens() + first.estimatedToolTokens());
        assertEquals(2, first.messageCount());
    }
}
