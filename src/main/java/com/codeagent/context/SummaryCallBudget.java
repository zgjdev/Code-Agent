package com.codeagent.context;

import com.codeagent.llm.LlmClient;

/** Per-scope summary call budget. A structured operation reserves its possible repair up front. */
public final class SummaryCallBudget {
    private static final int CALLS_PER_OPERATION = 2;
    private final int maxCalls;
    private int reservedCalls;
    private int completedCalls;
    private long inputTokens;
    private long outputTokens;
    private long cachedInputTokens;

    public SummaryCallBudget(int maxCalls) {
        if (maxCalls < 0) throw new IllegalArgumentException("negative summary call budget");
        this.maxCalls = maxCalls;
    }

    /** Returns null when an entire operation, including its repair, cannot be reserved. */
    public synchronized Reservation reserveOperation() {
        if (remainingCalls() < CALLS_PER_OPERATION) return null;
        reservedCalls += CALLS_PER_OPERATION;
        return new Reservation();
    }

    public synchronized int remainingCalls() {
        return maxCalls - completedCalls - reservedCalls;
    }

    /** Counts attempted provider calls, including calls which throw before reporting usage. */
    public synchronized int completedCalls() {
        return completedCalls;
    }

    public synchronized Usage usage() {
        return new Usage(inputTokens, outputTokens, cachedInputTokens);
    }

    public record Usage(long inputTokens, long outputTokens, long cachedInputTokens) {}

    public final class Reservation implements AutoCloseable {
        private int remaining = CALLS_PER_OPERATION;
        private boolean awaitingUsage;
        private boolean closed;

        private Reservation() {}

        /** Invoke immediately before each actual call, including StructuredJsonExecutor repairs. */
        public void beforeCall() {
            synchronized (SummaryCallBudget.this) {
                if (closed || remaining == 0 || awaitingUsage)
                    throw new IllegalStateException("summary call reservation unavailable");
                remaining--;
                reservedCalls--;
                completedCalls++;
                awaitingUsage = true;
            }
        }

        /** Record per-attempt usage, never the executor's aggregate response a second time. */
        public void recordUsage(LlmClient.ChatResponse response) {
            synchronized (SummaryCallBudget.this) {
                if (closed || !awaitingUsage)
                    throw new IllegalStateException("summary call usage already recorded or not started");
                awaitingUsage = false;
                if (response != null) {
                    inputTokens += Math.max(0, response.inputTokens());
                    outputTokens += Math.max(0, response.outputTokens());
                    cachedInputTokens += Math.max(0, response.cachedInputTokens());
                }
            }
        }

        @Override public void close() {
            synchronized (SummaryCallBudget.this) {
                if (closed) return;
                reservedCalls -= remaining;
                remaining = 0;
                closed = true;
            }
        }
    }
}
