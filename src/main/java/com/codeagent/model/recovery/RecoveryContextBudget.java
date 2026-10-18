package com.codeagent.model.recovery;

/** The remaining input space after ordinary messages, tools and output reserve are deducted. */
public record RecoveryContextBudget(long availableInputBudget, int maximumTokens, int outputReserve) {
    public RecoveryContextBudget {
        if (availableInputBudget < 0 || maximumTokens < 0 || outputReserve < 0)
            throw new IllegalArgumentException("Negative recovery budget");
    }
    public int parentLimit() {
        long fifteenPercent = availableInputBudget / 100 * 15 + availableInputBudget % 100 * 15 / 100;
        return (int) Math.min(Math.min(4096, maximumTokens), fifteenPercent);
    }
}
