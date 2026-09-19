package com.codeagent.agent;

import com.codeagent.plan.Task;

public record StepReviewRequest(String goal,
                                Task task,
                                String stepResult,
                                TaskVerificationReport verificationReport) {
    public StepReviewRequest {
        goal = goal == null ? "" : goal;
        stepResult = stepResult == null ? "" : stepResult;
    }
}
