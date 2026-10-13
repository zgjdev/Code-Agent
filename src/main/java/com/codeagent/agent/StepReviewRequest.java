package com.codeagent.agent;

import com.codeagent.plan.Task;

public record StepReviewRequest(String goal,
                                Task task,
                                String stepResult,
                                TaskVerificationReport verificationReport,
                                TaskReviewDiff reviewDiff) {
    public StepReviewRequest(String goal, Task task, String stepResult, TaskVerificationReport verificationReport) {
        this(goal, task, stepResult, verificationReport, null);
    }
    public StepReviewRequest {
        goal = goal == null ? "" : goal;
        stepResult = stepResult == null ? "" : stepResult;
    }
}
