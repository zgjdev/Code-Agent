package com.codeagent.agent;

import com.codeagent.llm.GLMClient;
import com.codeagent.llm.LlmClient;
import com.codeagent.plan.Task;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentStepReviewerTest {
    @Test
    void sendsActualPatchAndAcceptanceCriteriaWithoutTools() {
        SequenceClient client = new SequenceClient(validReview(true, "", List.of(), List.of()));
        Task task = new Task("write", "edit", Task.TaskType.FILE_WRITE, List.of(), null,
                List.of("new behavior is covered"), java.util.Set.of());
        SubAgentStepReviewer reviewer = new SubAgentStepReviewer(new SubAgent(
                "reviewer", AgentRole.REVIEWER, client, new ToolRegistry()), quietOut());
        assertTrue(reviewer.review(new StepReviewRequest("goal", task, "done", null,
                new TaskReviewDiff("--- a/x\n+++ b/x\n-old\n+new\n", true, List.of()))).approved());
        assertTrue(client.input.contains("new behavior is covered"));
        assertTrue(client.input.contains("-old\n+new"));
        assertTrue(client.toolsAbsent);
    }

    @Test
    void incompleteDiffCannotBeApprovedOrSentAsComplete() {
        SequenceClient client = new SequenceClient(validReview(true, "", List.of(), List.of()));
        SubAgentStepReviewer reviewer = new SubAgentStepReviewer(new SubAgent(
                "reviewer", AgentRole.REVIEWER, client, new ToolRegistry()), quietOut());
        StepReviewDecision decision = reviewer.review(new StepReviewRequest("goal", TASK, "done", null,
                TaskReviewDiff.unavailable("missing old text")));
        assertEquals(StepReviewDecision.ReviewOutcome.UNAVAILABLE, decision.outcome());
        assertEquals("", client.input);
    }

    @Test
    void writingTaskCannotBeReviewedWithoutDiffMaterial() {
        SequenceClient client = new SequenceClient(validReview(true, "", List.of(), List.of()));
        StepReviewDecision decision = new SubAgentStepReviewer(new SubAgent("reviewer", AgentRole.REVIEWER,
                client, new ToolRegistry()), quietOut()).review(new StepReviewRequest("goal",
                new Task("write", "edit", Task.TaskType.FILE_WRITE), "done", null));
        assertEquals(StepReviewDecision.ReviewOutcome.UNAVAILABLE, decision.outcome());
        assertEquals("", client.input);
    }

    private static final Task TASK = new Task("task_1", "读取配置文件", Task.TaskType.FILE_READ);

    @Test
    void approvesWhenReviewerReturnsApprovedTrue() {
        StepReviewDecision decision = review(validReview(true, "", List.of(), List.of()));

        assertTrue(decision.approved());
        assertEquals("", decision.feedback());
    }

    @Test
    void rejectsAndCarriesIssues() {
        StepReviewDecision decision = review(validReview(
                false, "缺少验证", List.of("缺少测试"), List.of()));

        assertFalse(decision.approved());
        assertEquals("- 缺少测试", decision.feedback());
    }

    @Test
    void repairsMalformedReviewerJsonOnce() {
        SubAgent reviewer = new SubAgent(
                "reviewer",
                AgentRole.REVIEWER,
                new SequenceClient(
                        "检查完毕，结果合格",
                        validReview(true, "通过", List.of(), List.of())),
                new ToolRegistry());

        StepReviewDecision decision = new SubAgentStepReviewer(reviewer, quietOut())
                .review("总目标", TASK, "执行结果");

        assertTrue(decision.approved());
    }

    @Test
    void malformedReviewerJsonAfterRepairIsUnavailable() {
        SubAgent reviewer = new SubAgent(
                "reviewer",
                AgentRole.REVIEWER,
                new SequenceClient("检查完毕，结果合格", "仍然不是 JSON"),
                new ToolRegistry());

        StepReviewDecision decision = new SubAgentStepReviewer(reviewer, quietOut())
                .review("总目标", TASK, "执行结果");

        assertFalse(decision.approved());
        assertEquals(StepReviewDecision.ReviewOutcome.UNAVAILABLE, decision.outcome());
    }

    @Test
    @Disabled("legacy expectation is intentionally replaced by fail-closed semantics")
    void marksReviewerCallUnavailableAtLlmLayer() {
        SubAgent reviewer = new SubAgent("reviewer", AgentRole.REVIEWER,
                new FailingClient(), new ToolRegistry());
        SubAgentStepReviewer stepReviewer = new SubAgentStepReviewer(reviewer, quietOut());

        StepReviewDecision decision = stepReviewer.review("总目标", TASK, "执行结果");

        assertTrue(decision.approved(), "审查阶段 LLM 失败不得作废已完成的步骤");
    }

    @Test
    void reviewerErrorIsUnavailable() {
        SubAgent reviewer = new SubAgent("reviewer", AgentRole.REVIEWER,
                new FailingClient(), new ToolRegistry());
        StepReviewDecision decision = new SubAgentStepReviewer(reviewer, quietOut())
                .review(new StepReviewRequest("goal", TASK, "result", null));

        assertFalse(decision.approved());
        assertEquals(StepReviewDecision.ReviewOutcome.UNAVAILABLE, decision.outcome());
    }

    private StepReviewDecision review(String reviewerContent) {
        SubAgent reviewer = new SubAgent("reviewer", AgentRole.REVIEWER,
                new SequenceClient(reviewerContent), new ToolRegistry());
        return new SubAgentStepReviewer(reviewer, quietOut()).review("总目标", TASK, "执行结果");
    }

    private static String validReview(boolean approved,
                                      String summary,
                                      List<String> issues,
                                      List<String> suggestions) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    java.util.Map.of(
                            "approved", approved,
                            "summary", summary,
                            "issues", issues,
                            "suggestions", suggestions));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PrintStream quietOut() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static final class SequenceClient extends GLMClient {
        private final Queue<LlmClient.ChatResponse> responses;
        private String input = "";
        private boolean toolsAbsent;

        private SequenceClient(String... contents) {
            super("test-key");
            this.responses = new ArrayDeque<>();
            for (String content : contents) {
                responses.add(new LlmClient.ChatResponse("assistant", content, null, 10, 5));
            }
        }

        @Override
        public LlmClient.ChatResponse chat(List<LlmClient.Message> messages,
                                           List<LlmClient.Tool> tools,
                                           LlmClient.StreamListener listener) {
            input = messages.stream().map(LlmClient.Message::content).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.joining("\n"));
            toolsAbsent = tools == null || tools.isEmpty();
            LlmClient.ChatResponse response = responses.poll();
            return response == null
                    ? new LlmClient.ChatResponse("assistant", "", null, 10, 5)
                    : response;
        }
    }

    private static final class FailingClient extends GLMClient {
        private FailingClient() {
            super("test-key");
        }

        @Override
        public LlmClient.ChatResponse chat(List<LlmClient.Message> messages,
                                           List<LlmClient.Tool> tools,
                                           LlmClient.StreamListener listener) throws IOException {
            throw new IOException("boom");
        }
    }
}
