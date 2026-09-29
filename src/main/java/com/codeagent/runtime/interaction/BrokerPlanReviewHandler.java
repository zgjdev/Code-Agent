package com.codeagent.runtime.interaction;

import com.codeagent.agent.PlanExecuteAgent;
import com.codeagent.plan.ExecutionPlan;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class BrokerPlanReviewHandler implements PlanExecuteAgent.PlanReviewHandler {
    private static final Set<String> ACTIONS = Set.of("execute", "supplement", "cancel");

    private final InteractionBroker broker;
    private final Consumer<InteractionRequest> notifier;

    public BrokerPlanReviewHandler(
            InteractionBroker broker,
            Consumer<InteractionRequest> notifier) {
        this.broker = broker;
        this.notifier = notifier == null ? request -> { } : notifier;
    }

    @Override
    public PlanExecuteAgent.PlanReviewDecision review(String goal, ExecutionPlan plan) {
        ExecutionInteractionContext.Identity identity = ExecutionInteractionContext.current();
        if (identity == null) {
            return PlanExecuteAgent.PlanReviewDecision.cancel();
        }
        InteractionRequest request = new InteractionRequest(
                UUID.randomUUID().toString(),
                identity.executionId(),
                identity.sessionId(),
                InteractionKind.PLAN_REVIEW,
                ACTIONS,
                plan.visualize());
        try {
            var future = broker.request(request);
            notifier.accept(request);
            InteractionResponse response = future.join();
            return switch (response.action()) {
                case "execute" -> PlanExecuteAgent.PlanReviewDecision.execute();
                case "supplement" -> PlanExecuteAgent.PlanReviewDecision.supplement(response.text());
                case "cancel" -> PlanExecuteAgent.PlanReviewDecision.cancel();
                default -> throw new IllegalStateException("Unexpected plan review action: " + response.action());
            };
        } catch (CompletionException | IllegalStateException e) {
            return PlanExecuteAgent.PlanReviewDecision.cancel();
        }
    }
}
