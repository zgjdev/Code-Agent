package com.codeagent.agent;

/**
 * 单步审查结论。替代此前"结论藏在 RESULT 文本里再二次解析"的做法。
 */
public record StepReviewDecision(ReviewOutcome outcome, String feedback) {

    public enum ReviewOutcome { APPROVED, REJECTED, UNAVAILABLE }

    public StepReviewDecision(boolean approved, String feedback) {
        this(approved ? ReviewOutcome.APPROVED : ReviewOutcome.REJECTED, feedback);
    }

    public boolean approved() {
        return outcome == ReviewOutcome.APPROVED;
    }

    public static StepReviewDecision approve() {
        return new StepReviewDecision(ReviewOutcome.APPROVED, "");
    }

    public static StepReviewDecision reject(String feedback) {
        return new StepReviewDecision(ReviewOutcome.REJECTED, feedback == null ? "" : feedback);
    }

    public static StepReviewDecision unavailable(String feedback) {
        return new StepReviewDecision(ReviewOutcome.UNAVAILABLE, feedback == null ? "" : feedback);
    }
}
