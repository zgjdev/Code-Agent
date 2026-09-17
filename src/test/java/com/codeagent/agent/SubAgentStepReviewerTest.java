package com.codeagent.agent;

import com.codeagent.llm.GLMClient;
import com.codeagent.llm.LlmClient;
import com.codeagent.plan.Task;
import com.codeagent.tool.ToolRegistry;
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

    private static final Task TASK = new Task("task_1", "读取配置文件", Task.TaskType.FILE_READ);

    @Test
    void approvesWhenReviewerReturnsApprovedTrue() {
        StepReviewDecision decision = review("{\"approved\": true, \"issues\": []}");

        assertTrue(decision.approved());
        assertEquals("", decision.feedback());
    }

    @Test
    void rejectsAndCarriesIssues() {
        StepReviewDecision decision = review("{\"approved\": false, \"issues\": [\"缺少测试\"]}");

        assertFalse(decision.approved());
        assertEquals("- 缺少测试", decision.feedback());
    }

    @Test
    void approvesWhenReviewerCallFailsAtLlmLayer() {
        SubAgent reviewer = new SubAgent("reviewer", AgentRole.REVIEWER,
                new FailingClient(), new ToolRegistry());
        SubAgentStepReviewer stepReviewer = new SubAgentStepReviewer(reviewer, quietOut());

        StepReviewDecision decision = stepReviewer.review("总目标", TASK, "执行结果");

        assertTrue(decision.approved(), "审查阶段 LLM 失败不得作废已完成的步骤");
    }

    private StepReviewDecision review(String reviewerContent) {
        SubAgent reviewer = new SubAgent("reviewer", AgentRole.REVIEWER,
                new OneShotClient(reviewerContent), new ToolRegistry());
        return new SubAgentStepReviewer(reviewer, quietOut()).review("总目标", TASK, "执行结果");
    }

    private static PrintStream quietOut() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static final class OneShotClient extends GLMClient {
        private final Queue<LlmClient.ChatResponse> responses;

        private OneShotClient(String content) {
            super("test-key");
            this.responses = new ArrayDeque<>(List.of(
                    new LlmClient.ChatResponse("assistant", content, null, 10, 5)));
        }

        @Override
        public LlmClient.ChatResponse chat(List<LlmClient.Message> messages,
                                          List<LlmClient.Tool> tools,
                                          LlmClient.StreamListener listener) {
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
