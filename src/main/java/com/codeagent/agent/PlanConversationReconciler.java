package com.codeagent.agent;

import com.codeagent.history.ParentConversationContext;
import com.codeagent.history.SessionEvent;
import com.codeagent.history.SessionEventDraft;
import com.codeagent.history.SessionProjection;
import com.codeagent.llm.LlmClient;
import com.codeagent.plan.ExecutionPlan;
import com.codeagent.plan.PlanConversationResultBuilder;
import com.codeagent.plan.PlanStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reconciles PlanStateStore and the durable parent-session Plan conversation turn. */
public final class PlanConversationReconciler {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanConversationResultBuilder resultBuilder = new PlanConversationResultBuilder();

    public void reconcile(PlanStateStore store,
                          ParentConversationContext context,
                          Path workspace,
                          String sessionId) throws SQLException, IOException {
        if (store == null || context == null || context.sessionHandle() == null
                || sessionId == null || sessionId.isBlank()) {
            return;
        }

        Optional<PlanStateStore.StoredPlanInfo> activeOptional =
                store.findActiveRecord(workspace, sessionId);
        SessionProjection projection = context.projection();
        if (projection == null) {
            return;
        }

        List<SessionProjection.OpenTurn> openTurns =
                new ArrayList<>(projection.openTurns().values());

        if (activeOptional.isPresent()) {
            PlanStateStore.StoredPlanInfo active = activeOptional.get();
            SessionProjection.OpenTurn matching = openTurns.stream()
                    .filter(turn -> turn.planIds().contains(active.planId()))
                    .findFirst()
                    .orElse(null);

            if (matching == null) {
                if (openTurns.size() > 1) {
                    throw new IOException("multiple open Plan turns cannot be reconciled safely");
                }
                if (openTurns.size() == 1) {
                    SessionProjection.OpenTurn existing = openTurns.get(0);
                    appendTurnStart(context, existing.turnId(), active.planId(), true);
                } else {
                    String turnId = "plan-turn-" + UUID.randomUUID();
                    appendTurnStart(context, turnId, active.planId(), false);
                    appendTopLevelMessage(context, turnId, active.planId(), "user",
                            semanticUser(active));
                }
                context.synchronizeProviderFromProjection();
                return;
            }

            if (!active.planId().equals(matching.activePlanId())) {
                appendTurnStart(context, matching.turnId(), active.planId(), true);
                context.synchronizeProviderFromProjection();
            }
            return;
        }

        for (SessionProjection.OpenTurn turn : openTurns) {
            Optional<PlanStateStore.StoredPlan> storedOptional = store.findById(turn.activePlanId());
            if (storedOptional.isEmpty()) {
                closeOrphaned(context, turn);
                continue;
            }
            PlanStateStore.StoredPlan stored = storedOptional.get();
            if (stored.info().active()) {
                closeOrphaned(context, turn);
                continue;
            }

            boolean assistantAlreadyPresent = context.projection().topLevelConversation().stream()
                    .anyMatch(node -> turn.turnId().equals(node.turnId())
                            && node.kind() == SessionProjection.ConversationKind.ASSISTANT);
            if (!assistantAlreadyPresent) {
                appendTopLevelMessage(context, turn.turnId(), turn.activePlanId(), "assistant",
                        resultBuilder.build(stored.plan()));
            }
            appendTurnEnd(context, turn.turnId(), turn.activePlanId(),
                    stored.plan().getStatus().name().toLowerCase());
            context.synchronizeProviderFromProjection();
        }
    }

    private static String semanticUser(PlanStateStore.StoredPlanInfo active) {
        return active.policyInput() == null || active.policyInput().isBlank()
                ? active.goal()
                : active.policyInput();
    }

    private static void closeOrphaned(ParentConversationContext context,
                                      SessionProjection.OpenTurn turn) throws IOException {
        boolean assistantAlreadyPresent = context.projection().topLevelConversation().stream()
                .anyMatch(node -> turn.turnId().equals(node.turnId())
                        && node.kind() == SessionProjection.ConversationKind.ASSISTANT);
        if (!assistantAlreadyPresent) {
            appendTopLevelMessage(context, turn.turnId(), turn.activePlanId(), "assistant",
                    "⚠️ 该计划的持久化工作流状态不可用，本轮已关闭，未自动重试。");
        }
        appendTurnEnd(context, turn.turnId(), turn.activePlanId(), "orphaned");
        context.synchronizeProviderFromProjection();
    }

    static void appendTurnStart(ParentConversationContext context,
                                String turnId,
                                String planId,
                                boolean continuation) throws IOException {
        ObjectNode payload = JSON.createObjectNode()
                .put("turnId", turnId)
                .put("planId", planId)
                .put("mode", "plan");
        if (continuation) {
            payload.put("continuation", true);
        }
        context.append(new SessionEventDraft(
                SessionEvent.Types.TURN_START,
                "plan",
                "plan-agent",
                "plan-conversation",
                false,
                SessionEvent.SurfaceOperation.none(),
                payload));
    }

    static void appendTurnEnd(ParentConversationContext context,
                              String turnId,
                              String planId,
                              String status) throws IOException {
        ObjectNode payload = JSON.createObjectNode()
                .put("turnId", turnId)
                .put("planId", planId)
                .put("mode", "plan")
                .put("status", status == null ? "unknown" : status);
        context.append(new SessionEventDraft(
                SessionEvent.Types.TURN_END,
                "plan",
                "plan-agent",
                "plan-conversation",
                false,
                SessionEvent.SurfaceOperation.none(),
                payload));
    }

    static void appendTopLevelMessage(ParentConversationContext context,
                                      String turnId,
                                      String planId,
                                      String role,
                                      String content) throws IOException {
        if (content == null || content.isBlank()) {
            throw new IOException("top-level Plan conversation content must not be blank");
        }
        LlmClient.Message message = "assistant".equals(role)
                ? LlmClient.Message.assistant(content)
                : LlmClient.Message.user(content);
        ObjectNode payload = JSON.createObjectNode();
        payload.set("message", JSON.valueToTree(message));
        ObjectNode conversation = payload.putObject("conversation")
                .put("turnId", turnId)
                .put("planId", planId)
                .put("mode", "plan")
                .put("role", role)
                .put("content", content);
        context.append(new SessionEventDraft(
                "assistant".equals(role)
                        ? SessionEvent.Types.ASSISTANT_MESSAGE
                        : SessionEvent.Types.USER_MESSAGE,
                "plan",
                "plan-agent",
                "plan-conversation",
                false,
                SessionEvent.SurfaceOperation.append(),
                payload));
    }
}
