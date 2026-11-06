package com.codeagent.history;

import com.codeagent.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ParentConversationCompactionConcurrencyTest {
    @Test void summaryReleasesParentMonitorAndCannotCommitAfterHistoryChanges() throws Exception {
        CountDownLatch enteredSummary = new CountDownLatch(1);
        CountDownLatch releaseSummary = new CountDownLatch(1);
        LlmClient client = new LlmClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                enteredSummary.countDown();
                try {
                    if (!releaseSummary.await(10, TimeUnit.SECONDS)) throw new IOException("test summary timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                return new ChatResponse("assistant", "old summary", null, null, 0, 0, 0);
            }
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                return chat(messages, tools);
            }
            @Override public String getModelName() { return "fixture"; }
            @Override public String getProviderName() { return "fixture"; }
        };
        var parent = new ParentConversationContext(LlmClient.Message.system("system"), client);
        parent.providerMessages().addAll(List.of(LlmClient.Message.user("first"), LlmClient.Message.assistant("answer"),
                LlmClient.Message.user("second")));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var compact = executor.submit(() -> parent.compactNow("react", "test", "manual"));
            assertTrue(enteredSummary.await(5, TimeUnit.SECONDS));
            var lateMessage = LlmClient.Message.user("new goal while summary runs");
            executor.submit(() -> {
                synchronized (parent) { parent.providerMessages().add(lateMessage); }
            }).get(3, TimeUnit.SECONDS);
            releaseSummary.countDown();
            assertFalse(compact.get(5, TimeUnit.SECONDS).compacted());
            assertEquals(5, parent.providerMessages().size());
            assertEquals(lateMessage, parent.providerMessages().get(4));
        } finally {
            releaseSummary.countDown();
            executor.shutdownNow();
        }
    }
}
