package com.codeagent.agent;

import com.codeagent.plan.Task;

public interface StepReviewer {

    StepReviewDecision review(StepReviewRequest request);

    default StepReviewDecision review(String goal, Task task, String stepResult) {
        return review(new StepReviewRequest(goal, task, stepResult, null));
    }
}
