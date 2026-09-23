package com.codeagent.agent;

import com.codeagent.history.ParentConversationContext;
import com.codeagent.history.SessionEvent;
import com.codeagent.history.SessionEventDraft;
import com.codeagent.history.SessionProjection;
import com.codeagent.history.SessionStore;
import com.codeagent.llm.LlmClient;
import com.codeagent.plan.ExecutionPlan;
import com.codeagent.plan.PlanStateStore;
import com.codeagent.plan.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanConversationReconcilerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void createsMissingOpenTurnFromDurableActivePlanAndClosesItFromTerminalState() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history"));
             SessionStore.SessionHandle handle = sessions.create(new SessionStore.SessionCreateRequest(
                     workspace, "test", "model", null, "react", "agent"))) {
            ParentConversationContext context = context(handle);
            PlanStateStore plans = new PlanStateStore(tempDir.resolve("plans.db"));
            ExecutionPlan plan = plan("plan-1", "goal");
            plans.savePlan(workspace, handle.sessionId(), "original user request", plan);

            PlanConversationReconciler reconciler = new PlanConversationReconciler();
            reconciler.reconcile(plans, context, workspace, handle.sessionId());

            assertEquals(1, handle.projection().openTurns().size());
            SessionProjection.OpenTurn open = handle.projection().openTurns().values().iterator().next();
            assertEquals("plan-1", open.activePlanId());
            assertEquals(1, handle.projection().topLevelConversation().size());
            assertEquals("original user request",
                    handle.projection().conversationMessages().get(0).content());

            plan.markStarted();
            plans.checkpointPlan(plan);
            Task task = plan.getTask("task_1");
            task.markCompleted("persisted-result");
            plans.checkpointTask(plan.getId(), task);
            plan.markCompleted();
            plans.checkpointPlan(plan);

            reconciler.reconcile(plans, context, workspace, handle.sessionId());

            assertTrue(handle.projection().openTurns().isEmpty());
            assertEquals(2, handle.projection().topLevelConversation().size());
            assertEquals("user", handle.projection().conversationMessages().get(0).role());
            assertEquals("assistant", handle.projection().conversationMessages().get(1).role());
            assertTrue(handle.projection().conversationMessages().get(1).content()
                    .contains("persisted-result"));
        }
    }

    @Test
    void rebindsOneOpenTurnToReplacementActivePlanWithoutAddingSecondUserMessage() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace-replan"));
        try (SessionStore sessions = SessionStore.open(tempDir.resolve("history-replan"));
             SessionStore.SessionHandle handle = sessions.create(new SessionStore.SessionCreateRequest(
                     workspace, "test", "model", null, "react", "agent"))) {
            ParentConversationContext context = context(handle);
            PlanStateStore plans = new PlanStateStore(tempDir.resolve("replan.db"));
            PlanConversationReconciler reconciler = new PlanConversationReconciler();

            ExecutionPlan first = plan("plan-1", "goal");
            plans.savePlan(workspace, handle.sessionId(), "same user turn", first);
            reconciler.reconcile(plans, context, workspace, handle.sessionId());
            String turnId = handle.projection().openTurns().values().iterator().next().turnId();

            first.markFailed();
            plans.checkpointPlan(first);
            ExecutionPlan replacement = plan("plan-2", "replanned goal");
            plans.savePlan(workspace, handle.sessionId(), "same user turn", replacement);

            reconciler.reconcile(plans, context, workspace, handle.sessionId());

            SessionProjection.OpenTurn open = handle.projection().openTurns().get(turnId);
            assertEquals("plan-1", open.rootPlanId());
            assertEquals("plan-2", open.activePlanId());
            assertEquals(java.util.List.of("plan-1", "plan-2"), open.planIds());
            assertEquals(1, handle.projection().topLevelConversation().stream()
                    .filter(node -> node.kind() == SessionProjection.ConversationKind.USER)
                    .count());
        }
    }

    private ParentConversationContext context(SessionStore.SessionHandle handle) throws Exception {
        ParentConversationContext context =
                new ParentConversationContext(LlmClient.Message.system("system"));
        context.setSessionHandle(handle);
        ObjectNode payload = JSON.createObjectNode();
        payload.set("message", JSON.valueToTree(LlmClient.Message.system("system")));
        context.append(new SessionEventDraft(
                SessionEvent.Types.SYSTEM_MESSAGE,
                "react", "agent", "test", false,
                SessionEvent.SurfaceOperation.append(), payload));
        context.synchronizeProviderFromProjection();
        return context;
    }

    private static ExecutionPlan plan(String id, String goal) {
        ExecutionPlan plan = new ExecutionPlan(id, goal);
        plan.addTask(new Task("task_1", "work", Task.TaskType.ANALYSIS));
        assertTrue(plan.computeExecutionOrder());
        return plan;
    }
}
