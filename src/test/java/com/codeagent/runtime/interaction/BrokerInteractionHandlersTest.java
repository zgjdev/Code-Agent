package com.codeagent.runtime.interaction;

import com.codeagent.agent.PlanExecuteAgent;
import com.codeagent.hitl.ApprovalRequest;
import com.codeagent.hitl.ApprovalResult;
import com.codeagent.plan.ExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BrokerInteractionHandlersTest {

    @Test
    void planReviewPublishesIdentityAndMapsSupplement() throws Exception {
        InteractionBroker broker = new InteractionBroker();
        BrokerPlanReviewHandler handler = new BrokerPlanReviewHandler(broker, request -> { });
        CompletableFuture<PlanExecuteAgent.PlanReviewDecision> decision;
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (ExecutionInteractionContext.Scope ignored =
                     ExecutionInteractionContext.install("exec-1", "session-1")) {
            decision = CompletableFuture.supplyAsync(() ->
                    handler.review("goal", new ExecutionPlan("plan-1", "goal")), executor);
        }

        InteractionRequest request = awaitPending(broker);
        assertEquals("exec-1", request.executionId());
        assertEquals("session-1", request.sessionId());
        assertEquals(InteractionKind.PLAN_REVIEW, request.kind());
        assertTrue(broker.respond(request.interactionId(), "supplement", "add tests"));
        assertEquals(PlanExecuteAgent.PlanReviewAction.SUPPLEMENT,
                decision.get(2, TimeUnit.SECONDS).action());
        assertEquals("add tests", decision.get().feedback());
        executor.shutdownNow();
    }

    @Test
    void hitlMapsExplicitDecisionsAndCachesApproveAll() throws Exception {
        InteractionBroker broker = new InteractionBroker();
        BrokerHitlHandler handler = new BrokerHitlHandler(true, broker, request -> { });
        ApprovalRequest approval = ApprovalRequest.of("write_file", "{}", "test");
        CompletableFuture<ApprovalResult> result;
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (ExecutionInteractionContext.Scope ignored =
                     ExecutionInteractionContext.install("exec-1", "session-1")) {
            result = CompletableFuture.supplyAsync(() -> handler.requestApproval(approval), executor);
        }

        InteractionRequest request = awaitPending(broker);
        assertTrue(request.allowedActions().contains("approve_all"));
        assertTrue(broker.respond(request.interactionId(), "approve_all", ""));
        assertEquals(ApprovalResult.Decision.APPROVED_ALL,
                result.get(2, TimeUnit.SECONDS).decision());
        assertTrue(handler.isApprovedAllByTool("write_file"));
        assertEquals(ApprovalResult.Decision.APPROVED_ALL,
                handler.requestApproval(approval).decision());
        executor.shutdownNow();
    }

    @Test
    void missingExecutionIdentityFailsClosed() {
        InteractionBroker broker = new InteractionBroker();
        BrokerHitlHandler handler = new BrokerHitlHandler(true, broker, request -> { });

        ApprovalResult result = handler.requestApproval(
                ApprovalRequest.of("write_file", "{}", "test"));

        assertTrue(result.isRejected());
        assertTrue(broker.pending().isEmpty());
    }

    private static InteractionRequest awaitPending(InteractionBroker broker) throws Exception {
        for (int i = 0; i < 100; i++) {
            var pending = broker.pending();
            if (pending.isPresent()) {
                return pending.orElseThrow();
            }
            Thread.sleep(10);
        }
        fail("interaction was not published");
        return null;
    }
}
