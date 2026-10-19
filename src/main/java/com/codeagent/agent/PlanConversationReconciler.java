package com.codeagent.agent;

import com.codeagent.history.ParentConversationContext;
import com.codeagent.history.SessionEvent;
import com.codeagent.history.SessionEventDraft;
import com.codeagent.history.SessionProjection;
import com.codeagent.llm.LlmClient;
import com.codeagent.plan.PlanConversationResultBuilder;
import com.codeagent.plan.PlanStateStore;
import com.codeagent.runtime.interaction.ExecutionInteractionContext;
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
                    appendTurnStart(context, existing.turnId(), active.planId(), true,knownOwner(context,active.executionId()));
                    matching = context.projection().openTurns().get(existing.turnId());
                } else {
                    String turnId = "plan-turn-" + UUID.randomUUID();
                    appendTurnStart(context, turnId, active.planId(), false,knownOwner(context,active.executionId()));
                    matching = context.projection().openTurns().get(turnId);
                }
            } else if (!active.planId().equals(matching.activePlanId())) {
                appendTurnStart(context, matching.turnId(), active.planId(), true,knownOwner(context,active.executionId()));
                matching = context.projection().openTurns().get(matching.turnId());
            }

            var owned=active.executionId()==null?null:context.projection().executionEnvelopes().get(knownOwner(context,active.executionId()));
            if ((owned==null||owned.userSequence()==null)&&!hasTopLevelUser(context, matching.turnId())) {
                appendTopLevelMessage(context, matching.turnId(), matching.rootPlanId(), "user",
                        semanticUser(active),knownOwner(context,active.executionId()),null);
            }
            context.synchronizeProviderFromProjection();
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
                        resultBuilder.build(stored.plan()),knownOwner(context,stored.info().executionId()),null);
            }
            appendTurnEnd(context, turn.turnId(), turn.activePlanId(),
                    stored.plan().getStatus().name().toLowerCase(),knownOwner(context,stored.info().executionId()));
            context.synchronizeProviderFromProjection();
        }
    }

    private static String knownOwner(ParentConversationContext context,String executionId) {
        return executionId!=null&&context.projection().executionEnvelopes().containsKey(executionId)?executionId:null;
    }

    private static String semanticUser(PlanStateStore.StoredPlanInfo active) {
        return active.policyInput() == null || active.policyInput().isBlank()
                ? active.goal()
                : active.policyInput();
    }

    private static boolean hasTopLevelUser(ParentConversationContext context, String turnId) {
        return context.projection().topLevelConversation().stream()
                .anyMatch(node -> turnId.equals(node.turnId())
                        && node.kind() == SessionProjection.ConversationKind.USER);
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
        appendTurnStart(context,turnId,planId,continuation,currentExecutionId());
    }
    static void appendTurnStart(ParentConversationContext context,String turnId,String planId,boolean continuation,String executionId) throws IOException {
        ObjectNode payload = JSON.createObjectNode()
                .put("turnId", turnId)
                .put("planId", planId)
                .put("mode", "plan");
        if(executionId!=null)payload.put("executionId",executionId);
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
        appendTurnEnd(context,turnId,planId,status,currentExecutionId());
    }
    static void appendTurnEnd(ParentConversationContext context,String turnId,String planId,String status,String executionId) throws IOException {
        ObjectNode payload = JSON.createObjectNode()
                .put("turnId", turnId)
                .put("planId", planId)
                .put("mode", "plan")
                .put("status", status == null ? "unknown" : status);
        if(executionId!=null)payload.put("executionId",executionId);
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
        appendTopLevelMessage(context,turnId,planId,role,content,currentExecutionId(),null);
    }
    static void appendTopLevelMessage(ParentConversationContext context,String turnId,String planId,String role,String content,
                                      String executionId,LlmClient.Message providerMessage) throws IOException {
        if (content == null || content.isBlank()) {
            throw new IOException("top-level Plan conversation content must not be blank");
        }
        LlmClient.Message message = providerMessage!=null?providerMessage:"assistant".equals(role)
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
        if (executionId != null) {
            payload.put("executionId", executionId);
            conversation.put("executionId", executionId);
        }
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

    private static void addExecutionId(ObjectNode payload) {
        ExecutionInteractionContext.Identity execution = ExecutionInteractionContext.current();
        if (execution != null) {
            payload.put("executionId", execution.executionId());
        }
    }
    private static String currentExecutionId() {
        var identity=ExecutionInteractionContext.current();return identity==null?null:identity.executionId();
    }
}
