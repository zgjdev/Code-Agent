package com.codeagent.agent;

/**
 * 单步审查结论。替代此前"结论藏在 RESULT 文本里再二次解析"的做法。
 */
public record StepReviewDecision(boolean approved, String feedback) {

    public static StepReviewDecision approve() {
        return new StepReviewDecision(true, "");
    }

    public static StepReviewDecision reject(String feedback) {
        return new StepReviewDecision(false, feedback == null ? "" : feedback);
    }
}
