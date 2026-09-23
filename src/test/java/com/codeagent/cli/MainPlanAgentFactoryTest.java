package com.codeagent.cli;

import com.codeagent.agent.Agent;
import com.codeagent.agent.PipelineOptions;
import com.codeagent.agent.PlanExecuteAgent;
import com.codeagent.history.ConversationLedger;
import com.codeagent.history.SessionStore;
import com.codeagent.llm.GLMClient;
import com.codeagent.llm.LlmClient;
import com.codeagent.memory.MemoryManager;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainPlanAgentFactoryTest {

    @Test
    void planModeReusesReactToolRegistryMemoryManagerAndLedger(@TempDir Path tempDir)
            throws Exception {
        LlmClient llmClient = new GLMClient("test-key");
        ToolRegistry sharedToolRegistry = new ToolRegistry();
        Agent reactAgent = new Agent(llmClient, sharedToolRegistry);
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "shared-session");
        reactAgent.setConversationLedger(ledger);
        MemoryManager sharedMemoryManager = reactAgent.getMemoryManager();

        try (SessionStore sessions = SessionStore.open(tempDir.resolve("session-store"));
             SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(
                     tempDir, "glm", "test", null, "react", "agent"))) {
            reactAgent.attachSession(session);

            PlanExecuteAgent planAgent = Main.createPlanAgent(
                    llmClient,
                    reactAgent,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel()
            );

            assertSame(sharedToolRegistry, readField(planAgent, "toolRegistry"));
            assertSame(sharedMemoryManager, readField(planAgent, "memoryManager"));
            assertSame(ledger, readField(planAgent, "conversationLedger"));
            assertSame(ledger, readField(readField(planAgent, "planner"), "conversationLedger"));
            assertSame(session, readField(planAgent, "parentSession"));
            assertSame(reactAgent.getParentConversationContext(),
                    readField(planAgent, "parentConversationContext"));
        }
    }

    @Test
    void planModeEnablesHumanGateAndStepReview(@TempDir Path tempDir) throws Exception {
        LlmClient llmClient = new GLMClient("test-key");
        ToolRegistry sharedToolRegistry = new ToolRegistry();
        Agent reactAgent = new Agent(llmClient, sharedToolRegistry);
        reactAgent.setConversationLedger(
                ConversationLedger.open(tempDir.resolve("history"), "shared-session"));

        PlanExecuteAgent planAgent = Main.createPlanAgent(
                llmClient,
                reactAgent,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel()
        );

        PipelineOptions options = (PipelineOptions) readField(planAgent, "pipelineOptions");
        assertSame(PipelineOptions.FULL_PRESET, options);
        assertTrue(options.stepReview(), "stepReview 开关决定每个任务是否自动构造 Reviewer");
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
