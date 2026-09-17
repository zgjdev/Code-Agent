package com.codeagent.agent;

import com.codeagent.plan.Task;

import java.io.PrintStream;

/**
 * 用 Reviewer 子 Agent 实现单步审查。
 * 两层失败策略不同：审查调用本身失败时放行（不作废已完成的步骤），
 * 而审查结论无法解析时不通过（失败关闭，见 ReviewResponseParser）。
 */
final class SubAgentStepReviewer implements StepReviewer {

    private final SubAgent reviewer;
    private final PrintStream out;

    SubAgentStepReviewer(SubAgent reviewer, PrintStream out) {
        this.reviewer = reviewer;
        this.out = out == null ? System.out : out;
    }

    @Override
    public StepReviewDecision review(String goal, Task task, String stepResult) {
        String originalTask = "总目标：" + goal + "\n当前任务：" + task.getDescription();
        AgentMessage reviewResult = reviewer.review(originalTask, stepResult, out);
        reviewer.clearHistory();

        if (reviewResult.type() == AgentMessage.Type.ERROR) {
            return StepReviewDecision.approve();
        }
        if (ReviewResponseParser.parseApproved(reviewResult.content())) {
            return StepReviewDecision.approve();
        }
        return StepReviewDecision.reject(ReviewResponseParser.parseIssues(reviewResult.content()));
    }
}
