package com.codeagent.agent;

import com.codeagent.llm.LlmClient;
import com.codeagent.memory.MemoryManager;
import com.codeagent.plan.EvidenceType;
import com.codeagent.plan.ExecutionPlan;
import com.codeagent.plan.Planner;
import com.codeagent.plan.Task;
import com.codeagent.plan.TaskResourceClaims;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanDiffEvidenceIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void noOpWriteDoesNotSatisfyRequiredDiffEvidence() throws Exception {
        Path source = tempDir.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "same\n");

        QueueLlmClient llmClient = new QueueLlmClient(List.of(
                noOpWrite("call-1"), finalAnswer(),
                noOpWrite("call-2"), finalAnswer(),
                noOpWrite("call-3"), finalAnswer()));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Planner planner = new DiffPlanner(llmClient);
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                registry,
                planner,
                new MemoryManager(llmClient),
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()),
                PipelineOptions.PLAN_PRESET);

        String result = agent.run("修改 src/App.java");

        assertTrue(result.contains("未验证"), result);
        assertEquals(6, llmClient.calls());
        assertEquals("same\n", Files.readString(source));
    }

    private static LlmClient.ChatResponse noOpWrite(String id) {
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                id,
                new LlmClient.ToolCall.Function(
                        "write_file",
                        "{\"path\":\"src/App.java\",\"content\":\"same\\n\"}"));
        return new LlmClient.ChatResponse("assistant", "", null, List.of(call), 1, 1);
    }

    private static LlmClient.ChatResponse finalAnswer() {
        return new LlmClient.ChatResponse("assistant", "done", null, 1, 1);
    }

    private static final class DiffPlanner extends Planner {
        private DiffPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-diff", goal);
            plan.addTask(new Task(
                    "task_1",
                    "修改 src/App.java",
                    Task.TaskType.FILE_WRITE,
                    List.of(),
                    new TaskResourceClaims(
                            List.of("src/App.java"),
                            List.of("src/App.java"),
                            false),
                    List.of("文件产生实际修改"),
                    Set.of(EvidenceType.DIFF)));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static final class QueueLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private int calls;

        private QueueLlmClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        int calls() {
            return calls;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            calls++;
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("unexpected extra LLM call");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
