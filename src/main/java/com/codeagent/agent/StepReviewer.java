package com.codeagent.agent;

import com.codeagent.plan.Task;

public interface StepReviewer {

    StepReviewDecision review(String goal, Task task, String stepResult);
}
