package com.codeagent.history;

import com.codeagent.context.MeasuredUsage;
import com.codeagent.context.*;
import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates an event stream and derives the model-visible session surface. */
public final class SessionReplayer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KNOWN_TYPES = Set.of(
            SessionEvent.Types.CONTEXT_EDIT,
            SessionEvent.Types.SESSION_START,
            SessionEvent.Types.SESSION_END,
            SessionEvent.Types.SESSION_INTERRUPT,
            SessionEvent.Types.TURN_START,
            SessionEvent.Types.TURN_END,
            SessionEvent.Types.EXECUTION_START,
            SessionEvent.Types.EXECUTION_END,
            SessionEvent.Types.REQUEST_STARTED,
            SessionEvent.Types.REQUEST_SNAPSHOT,
            SessionEvent.Types.REQUEST_FINISHED,
            SessionEvent.Types.REQUEST_FAILED,
            SessionEvent.Types.SYSTEM_MESSAGE,
            SessionEvent.Types.USER_MESSAGE,
            SessionEvent.Types.ASSISTANT_MESSAGE,
            SessionEvent.Types.TOOL_CALL,
            SessionEvent.Types.TOOL_EXECUTION_STARTED,
            SessionEvent.Types.TOOL_RESULT,
            SessionEvent.Types.PROVIDER_USAGE,
            SessionEvent.Types.SURFACE_CLEAR,
            SessionEvent.Types.IMAGE_PRUNED,
            SessionEvent.Types.COMPACTION_START,
            SessionEvent.Types.COMPACTION_SUMMARY,
            SessionEvent.Types.COMPACTION_END,
            SessionEvent.Types.RETRY_START,
            SessionEvent.Types.RETRY_END,
            SessionEvent.Types.LEGACY_EVENT,
            SessionEvent.Types.CHILD_RESULT);

    public SessionProjection replay(SessionManifest manifest, List<SessionEvent> events) {
        if (manifest == null) {
            throw new CorruptSessionException("missing session manifest");
        }
        MutableProjection state = new MutableProjection(manifest.closed());
        long expectedSequence = 0;
        for (SessionEvent event : events == null ? List.<SessionEvent>of() : events) {
            validate(manifest, event, expectedSequence);
            state.requiredSchemaVersion = Math.max(state.requiredSchemaVersion, event.schemaVersion());
            expectedSequence++;
            state.lastAppliedSequence = event.sequence();
            state.cleanlyClosed = false;
            if (!KNOWN_TYPES.contains(event.type())) {
                if (event.ignorable()) {
                    continue;
                }
                throw new UnsupportedSessionEventException("unsupported required event: " + event.type());
            }
            apply(state, event);
        }
        for (String compactionId : state.compactions.keySet()) {
            state.warnings.add("incomplete compaction ignored: " + compactionId);
        }
        return state.freeze();
    }

    public SessionProjection replayFrom(SessionManifest manifest, SessionProjection checkpoint,
                                        List<SessionEvent> tail) {
        if (manifest == null || checkpoint == null) {
            throw new CorruptSessionException("missing checkpoint replay state");
        }
        MutableProjection state = new MutableProjection(checkpoint);
        long expectedSequence = checkpoint.lastAppliedSequence() + 1;
        for (SessionEvent event : tail == null ? List.<SessionEvent>of() : tail) {
            validate(manifest, event, expectedSequence++);
            state.requiredSchemaVersion = Math.max(state.requiredSchemaVersion, event.schemaVersion());
            state.lastAppliedSequence = event.sequence();
            state.cleanlyClosed = false;
            if (!KNOWN_TYPES.contains(event.type())) {
                if (event.ignorable()) continue;
                throw new UnsupportedSessionEventException("unsupported required event: " + event.type());
            }
            apply(state, event);
        }
        for (String compactionId : state.compactions.keySet()) {
            state.warnings.add("incomplete compaction ignored: " + compactionId);
        }
        return state.freeze();
    }

    private static void validate(SessionManifest manifest, SessionEvent event, long expectedSequence) {
        if (event == null) {
            throw new CorruptSessionException("null event at sequence " + expectedSequence);
        }
        if (!manifest.sessionId().equals(event.sessionId())) {
            throw new CorruptSessionException("event belongs to another session: " + event.sessionId());
        }
        if (event.sequence() != expectedSequence) {
            throw new CorruptSessionException(
                    "expected sequence " + expectedSequence + " but found " + event.sequence());
        }
        if (event.schemaVersion() > SessionEvent.CURRENT_SCHEMA_VERSION && !event.ignorable()) {
            throw new UnsupportedSessionEventException(
                    "unsupported schema version: " + event.schemaVersion());
        }
        if (event.schemaVersion() < 1) throw new CorruptSessionException("Invalid event schema version");
    }

    private static void apply(MutableProjection state, SessionEvent event) {
        state.requiredSchemaVersion = Math.max(state.requiredSchemaVersion, event.schemaVersion());
        JsonNode payload = event.payload();
        if (SessionEvent.Types.CONTEXT_EDIT.equals(event.type())) {
            if (event.schemaVersion() != 3 || event.ignorable() || !"none".equals(event.surface().op()))
                throw new CorruptSessionException("context/edit requires schema 3 and required semantics");
            try {
                if (JSON.writeValueAsBytes(payload).length > 1024 * 1024)
                    throw new CorruptSessionException("Context payload exceeds limit");
                ContextEditBatch batch = JSON.treeToValue(payload, ContextEditBatch.class);
                String digest = ContextDigests.canonicalDigest(batch);
                String previous = state.committedOperationDigests.get(batch.operationId());
                if (previous != null) {
                    if (!previous.equals(digest)) throw new CorruptSessionException("Context operation identity conflict");
                    return;
                }
                ContextScopeState scope = state.contextScopes.get(batch.scopeId());
                if (scope == null) throw new CorruptSessionException("Missing context scope");
                state.contextScopes.put(batch.scopeId(), ContextEditReplay.apply(scope, batch, null));
                state.committedOperationDigests.put(batch.operationId(), digest);
            } catch (Exception e) { throw new CorruptSessionException("Invalid context edit transaction", e); }
            return;
        }
        String compactionId = text(payload, "compactionId");
        if (SessionEvent.Types.COMPACTION_START.equals(event.type())) {
            require(compactionId, "compaction/start requires compactionId");
            if (state.compactions.putIfAbsent(compactionId, new ArrayList<>()) != null) {
                throw new CorruptSessionException("duplicate compaction start: " + compactionId);
            }
            state.compactionStarts.put(compactionId, event);
            return;
        }
        if (SessionEvent.Types.COMPACTION_END.equals(event.type())) {
            completeCompaction(state, event, compactionId);
            return;
        }
        if (isMessageEvent(event.type()) && compactionId != null && state.compactions.containsKey(compactionId)) {
            state.compactions.get(compactionId).add(event);
            return;
        }
        String requestId = text(payload, "requestId");
        if (SessionEvent.Types.ASSISTANT_MESSAGE.equals(event.type()) && requestId != null) {
            if (!state.incompleteRequests.contains(requestId)) {
                throw new CorruptSessionException("assistant event has no active request: " + requestId);
            }
            state.requestAssistants.computeIfAbsent(requestId, ignored -> new ArrayList<>()).add(event);
            return;
        }

        switch (event.type()) {
            case SessionEvent.Types.EXECUTION_START -> startExecution(state, event);
            case SessionEvent.Types.EXECUTION_END -> endExecution(state, event);
            case SessionEvent.Types.TURN_START -> startTurn(state, event);
            case SessionEvent.Types.TURN_END -> endTurn(state, event);
            case SessionEvent.Types.REQUEST_STARTED -> state.incompleteRequests.add(requiredText(payload, "requestId"));
            case SessionEvent.Types.REQUEST_FINISHED -> finishRequest(state, requiredText(payload, "requestId"));
            case SessionEvent.Types.REQUEST_FAILED -> failRequest(state, requiredText(payload, "requestId"));
            case SessionEvent.Types.PROVIDER_USAGE -> stageUsage(state, payload);
            case SessionEvent.Types.TOOL_CALL -> {
                String invocationId = requiredText(payload, "invocationId");
                state.pendingTools.put(invocationId, new SessionProjection.PendingToolInvocation(
                        invocationId, text(payload, "name"), text(payload, "arguments")));
            }
            case SessionEvent.Types.TOOL_RESULT -> {
                state.pendingTools.remove(requiredText(payload, "invocationId"));
                applySurface(state, event);
            }
            case SessionEvent.Types.SESSION_END -> state.cleanlyClosed = true;
            case SessionEvent.Types.USER_MESSAGE,
                 SessionEvent.Types.ASSISTANT_MESSAGE -> applyMessageEvent(state, event);
            case SessionEvent.Types.SYSTEM_MESSAGE,
                 SessionEvent.Types.IMAGE_PRUNED -> applySurface(state, event);
            case SessionEvent.Types.SURFACE_CLEAR -> {
                applySurface(state, event);
                state.conversation.clear();
                state.openTurns.clear();
            }
            default -> {
                // Lifecycle and diagnostic events do not directly change the active surface.
            }
        }
    }

    private static void startExecution(MutableProjection state, SessionEvent event) {
        String executionId = requiredText(event.payload(), "executionId");
        if (state.executionEnvelopes.containsKey(executionId)) {
            throw new CorruptSessionException("duplicate execution start: " + executionId);
        }
        JsonNode ordinalNode = event.payload() == null ? null : event.payload().get("ordinal");
        if (ordinalNode == null || !ordinalNode.isIntegralNumber()) {
            throw new CorruptSessionException("execution/start requires ordinal");
        }
        state.executionEnvelopes.put(executionId, new SessionProjection.ExecutionEnvelope(
                executionId, ordinalNode.longValue(), text(event.payload(), "explicitMode"),
                event.sequence(), null, null, null, null, null, null));
    }

    private static void endExecution(MutableProjection state, SessionEvent event) {
        String executionId = requiredText(event.payload(), "executionId");
        SessionProjection.ExecutionEnvelope envelope = state.executionEnvelopes.get(executionId);
        if (envelope == null) {
            throw new CorruptSessionException("execution end has no start: " + executionId);
        }
        if (envelope.ended()) {
            throw new CorruptSessionException("duplicate execution end: " + executionId);
        }
        String selectedMode = requiredText(event.payload(), "selectedMode");
        String outcome = requiredText(event.payload(), "outcome");
        String status = requiredText(event.payload(), "status");
        state.executionEnvelopes.put(executionId,
                envelope.ended(event.sequence(), selectedMode, outcome, status));
    }

    private static void finishRequest(MutableProjection state, String requestId) {
        if (!state.incompleteRequests.remove(requestId)) {
            throw new CorruptSessionException("request finished without start: " + requestId);
        }
        for (SessionEvent assistant : state.requestAssistants.getOrDefault(requestId, List.of())) {
            applyMessageEvent(state, assistant);
        }
        state.requestAssistants.remove(requestId);
        SessionProjection.MeasuredUsageFact usage = state.requestUsages.remove(requestId);
        if (usage != null) {
            state.lastCompletedUsage = usage;
        }
    }

    private static void failRequest(MutableProjection state, String requestId) {
        state.incompleteRequests.remove(requestId);
        state.requestAssistants.remove(requestId);
        state.requestUsages.remove(requestId);
    }

    private static void stageUsage(MutableProjection state, JsonNode payload) {
        String requestId = requiredText(payload, "requestId");
        if (!state.incompleteRequests.contains(requestId)) {
            throw new CorruptSessionException("usage event has no active request: " + requestId);
        }
        JsonNode value = payload == null ? null : payload.get("usage");
        if (value == null || value.isNull()) {
            throw new CorruptSessionException("provider usage event has no usage");
        }
        try {
            MeasuredUsage usage = new MeasuredUsage(
                    value.path("inputTokens").asInt(), value.path("outputTokens").asInt(),
                    value.path("cachedInputTokens").asInt(),
                    MeasuredUsage.InputScope.valueOf(requiredText(value, "inputScope")),
                    value.path("includesTools").asBoolean(), value.path("includesSystem").asBoolean(),
                    value.path("trusted").asBoolean(),
                    Instant.ofEpochMilli(value.path("measuredAtEpochMilli").asLong()));
            state.requestUsages.put(requestId, new SessionProjection.MeasuredUsageFact(
                    requestId, text(payload, "provider"), text(payload, "model"), usage));
        } catch (RuntimeException e) {
            throw new CorruptSessionException("invalid provider usage for request " + requestId, e);
        }
    }

    private static void completeCompaction(MutableProjection state, SessionEvent event, String compactionId) {
        require(compactionId, "compaction/end requires compactionId");
        List<SessionEvent> staged = state.compactions.remove(compactionId);
        if (staged == null) {
            throw new CorruptSessionException("compaction end without start: " + compactionId);
        }
        SessionEvent start = state.compactionStarts.remove(compactionId);
        if (!"completed".equals(text(event.payload(), "status"))) {
            return;
        }
        if (start != null && start.schemaVersion() >= 3) {
            completeExplicitCompaction(state, start, staged, event);
            return;
        }
        int insertionIndex = -1;
        for (SessionEvent stagedEvent : staged) {
            if ("replace".equals(stagedEvent.surface().op())) {
                applyCompactionConversation(state, stagedEvent);
                applySurface(state, stagedEvent);
                insertionIndex = indexOf(state.surface, stagedEvent.sequence()) + 1;
            } else if ("append".equals(stagedEvent.surface().op()) && insertionIndex >= 0) {
                state.surface.add(insertionIndex++, messageNode(stagedEvent));
                state.historyVersion++;
                synchronizeContextSources(state, stagedEvent);
            } else {
                applySurface(state, stagedEvent);
            }
        }
        state.compactionGeneration++;
    }

    private static void completeExplicitCompaction(MutableProjection state, SessionEvent start,
            List<SessionEvent> staged, SessionEvent end) {
        if (start.ignorable() || end.ignorable() || end.schemaVersion() != 3 || staged.size() != 1)
            throw new CorruptSessionException("Invalid required compaction transaction");
        SessionEvent summary = staged.get(0);
        if (summary.schemaVersion() != 3 || summary.ignorable()
                || !SessionEvent.Types.ASSISTANT_MESSAGE.equals(summary.type()) || !"none".equals(summary.surface().op()))
            throw new CorruptSessionException("Invalid compaction summary");
        if (!ContextDigests.canonicalDigest(List.of(start.payload(), summary.payload()))
                .equals(text(end.payload(), "transactionDigest"))) throw new CorruptSessionException("Compaction transaction digest mismatch");
        String scopeId = requiredText(start.payload(), "scopeId");
        ContextScopeState scope = state.contextScopes.get(scopeId);
        if (scope == null || scope.sourceRevision() != start.payload().path("expectedSourceRevision").asLong(-1)
                || scope.contextGeneration() != start.payload().path("expectedContextGeneration").asLong(-1))
            throw new CorruptSessionException("Stale compaction source");
        List<NodeId> ids = new ArrayList<>();
        try { for (JsonNode id : start.payload().path("orderedSourceIds")) ids.add(JSON.treeToValue(id, NodeId.class)); }
        catch (Exception invalid) { throw new CorruptSessionException("Invalid compaction sources", invalid); }
        if (ids.isEmpty()) throw new CorruptSessionException("Empty compaction sources");
        List<ContextNode> selected = new ArrayList<>();
        int from = -1;
        for (int i = 0; i < scope.sourceNodes().size(); i++) if (scope.sourceNodes().get(i).id().equals(ids.get(0))) { from = i; break; }
        if (from < 0 || from + ids.size() > scope.sourceNodes().size()) throw new CorruptSessionException("Missing compaction sources");
        selected.addAll(scope.sourceNodes().subList(from, from + ids.size()));
        if (!selected.stream().map(ContextNode::id).toList().equals(ids)
                || !ContextDigests.sourceDigest(selected).equals(text(start.payload(), "sourceDigest")))
            throw new CorruptSessionException("Compaction sources changed");
        Set<NodeId> replacedIds = new LinkedHashSet<>(ids);
        Set<String> retiredIds = new LinkedHashSet<>();
        for (JsonNode retired : start.payload().path("retiredEditIds")) {
            if (!retired.isTextual() || !retiredIds.add(retired.textValue()))
                throw new CorruptSessionException("Invalid compaction retirement");
        }
        Map<String, ContextEdit> activeEdits = new LinkedHashMap<>();
        scope.activeEdits().forEach(edit -> activeEdits.put(edit.editId(), edit));
        if (!activeEdits.keySet().containsAll(retiredIds)) throw new CorruptSessionException("Unknown compaction retirement");
        Set<NodeId> removedDependencyIds = new LinkedHashSet<>(replacedIds);
        for (String retired : retiredIds) activeEdits.get(retired).replacementNodes().forEach(n -> removedDependencyIds.add(n.id()));
        for (ContextEdit edit : scope.activeEdits()) {
            if (edit.orderedSourceIds().stream().anyMatch(replacedIds::contains)
                    && (!replacedIds.containsAll(edit.orderedSourceIds()) || !retiredIds.contains(edit.editId())))
                throw new CorruptSessionException("Compaction must fully cover and retire overlapping edits");
            if (!retiredIds.contains(edit.editId())
                    && edit.dependencies().stream().anyMatch(d -> removedDependencyIds.contains(d.nodeId())))
                throw new CorruptSessionException("Compaction must retire dependent edits");
        }
        List<Long> covered = new ArrayList<>();
        for (JsonNode id : start.payload().path("coveredConversationNodeIds")) {
            if (!id.isIntegralNumber()) throw new CorruptSessionException("Invalid conversation identity");
            covered.add(id.longValue());
        }
        var sourceSequences = ids.stream().map(NodeId::sourceSequence).collect(java.util.stream.Collectors.toSet());
        List<Long> actualCovered = state.conversation.stream().filter(n -> sourceSequences.contains(n.sequence()))
                .map(SessionProjection.ConversationNode::sequence).toList();
        if (!covered.equals(actualCovered) || covered.size() > state.conversation.size()
                || !state.conversation.subList(0, covered.size()).stream().map(SessionProjection.ConversationNode::sequence).toList().equals(covered))
            throw new CorruptSessionException("Compaction conversation coverage mismatch");
        int surfaceFrom = indexOf(state.surface, ids.get(0).sourceSequence());
        if (surfaceFrom < 0 || surfaceFrom + ids.size() > state.surface.size()
                || !state.surface.subList(surfaceFrom, surfaceFrom + ids.size()).stream().map(SessionProjection.SurfaceNode::sequence).toList()
                .equals(ids.stream().map(NodeId::sourceSequence).toList())) throw new CorruptSessionException("Compaction surface membership mismatch");
        state.surface.subList(surfaceFrom, surfaceFrom + ids.size()).clear();
        state.surface.add(surfaceFrom, messageNode(summary));
        if (!covered.isEmpty()) {
            state.conversation.subList(0, covered.size()).clear();
            state.conversation.add(0, new SessionProjection.ConversationNode(summary.sequence(), null, null, "summary",
                    messageNode(summary).message(), SessionProjection.ConversationKind.SUMMARY));
        }
        state.historyVersion++;
        state.compactionGeneration++;
        synchronizeContextSources(state, summary);
        var replacedScope = state.contextScopes.get(scopeId);
        state.contextScopes.put(scopeId, new ContextScopeState(scopeId, replacedScope.sourceRevision(),
                replacedScope.contextGeneration(), replacedScope.sourceNodes(),
                replacedScope.activeEdits().stream().filter(edit -> !retiredIds.contains(edit.editId())).toList()));
    }

    private static void applyMessageEvent(MutableProjection state, SessionEvent event) {
        indexExecutionMessage(state, event);
        applySurface(state, event);
        applyConversation(state, event);
    }

    private static void indexExecutionMessage(MutableProjection state, SessionEvent event) {
        String executionId = text(event.payload(), "executionId");
        if (executionId == null && event.payload() != null) {
            executionId = text(event.payload().get("conversation"), "executionId");
        }
        if (executionId == null) {
            return;
        }
        SessionProjection.ExecutionEnvelope envelope = state.executionEnvelopes.get(executionId);
        if (envelope == null || envelope.ended()) {
            throw new CorruptSessionException("message is outside execution envelope: " + executionId);
        }
        if (SessionEvent.Types.USER_MESSAGE.equals(event.type())) {
            if (envelope.userSequence() != null) {
                throw new CorruptSessionException("duplicate top-level execution user: " + executionId);
            }
            state.executionEnvelopes.put(executionId, envelope.withUser(event.sequence()));
        } else if (SessionEvent.Types.ASSISTANT_MESSAGE.equals(event.type())) {
            if (envelope.assistantSequence() != null) {
                throw new CorruptSessionException("duplicate top-level execution assistant: " + executionId);
            }
            state.executionEnvelopes.put(executionId, envelope.withAssistant(event.sequence()));
        }
    }

    private static void applyConversation(MutableProjection state, SessionEvent event) {
        JsonNode conversation = event.payload() == null ? null : event.payload().get("conversation");
        if (conversation == null || conversation.isNull() || !conversation.isObject()) {
            return;
        }
        String role = requiredText(conversation, "role");
        String content = requiredText(conversation, "content");
        String mode = text(conversation, "mode");
        String turnId = text(conversation, "turnId");
        String planId = text(conversation, "planId");
        SessionProjection.ConversationKind kind;
        LlmClient.Message message;
        if ("user".equals(role)) {
            kind = SessionProjection.ConversationKind.USER;
            message = LlmClient.Message.user(content);
        } else if ("assistant".equals(role)) {
            kind = SessionProjection.ConversationKind.ASSISTANT;
            message = LlmClient.Message.assistant(content);
        } else {
            throw new CorruptSessionException("unsupported conversation role: " + role);
        }
        if ("plan".equals(mode)) {
            require(turnId, "plan conversation event requires turnId");
            require(planId, "plan conversation event requires planId");
            SessionProjection.OpenTurn turn = state.openTurns.get(turnId);
            if (turn == null || !turn.planIds().contains(planId)) {
                throw new CorruptSessionException("plan conversation event has no matching open turn: " + turnId);
            }
        }
        state.conversation.add(new SessionProjection.ConversationNode(
                event.sequence(), turnId, planId, mode, message, kind));
    }

    private static void startTurn(MutableProjection state, SessionEvent event) {
        JsonNode payload = event.payload();
        String mode = text(payload, "mode");
        if (!"plan".equals(mode)) {
            return;
        }
        String turnId = requiredText(payload, "turnId");
        String planId = requiredText(payload, "planId");
        boolean continuation = payload != null && payload.path("continuation").asBoolean(false);
        SessionProjection.OpenTurn existing = state.openTurns.get(turnId);
        if (!continuation) {
            if (existing != null) {
                throw new CorruptSessionException("duplicate plan turn start: " + turnId);
            }
            state.openTurns.put(turnId, new SessionProjection.OpenTurn(
                    turnId, planId, planId, List.of(planId)));
            return;
        }
        if (existing == null) {
            throw new CorruptSessionException("plan continuation has no open turn: " + turnId);
        }
        List<String> planIds = new ArrayList<>(existing.planIds());
        if (!planIds.contains(planId)) {
            planIds.add(planId);
        }
        state.openTurns.put(turnId, new SessionProjection.OpenTurn(
                turnId, existing.rootPlanId(), planId, planIds));
    }

    private static void endTurn(MutableProjection state, SessionEvent event) {
        JsonNode payload = event.payload();
        String mode = text(payload, "mode");
        if (!"plan".equals(mode)) {
            return;
        }
        String turnId = requiredText(payload, "turnId");
        SessionProjection.OpenTurn removed = state.openTurns.remove(turnId);
        if (removed == null) {
            throw new CorruptSessionException("plan turn end has no open turn: " + turnId);
        }
    }

    private static void applyCompactionConversation(MutableProjection state, SessionEvent replacement) {
        Long startSequence = replacement.surface().startSequence();
        Long endSequence = replacement.surface().endSequence();
        if (startSequence == null || endSequence == null || startSequence > endSequence) {
            throw new CorruptSessionException("invalid conversation replacement range");
        }
        int insertionIndex = -1;
        for (int index = 0; index < state.conversation.size(); index++) {
            long sequence = state.conversation.get(index).sequence();
            if (sequence >= startSequence && sequence <= endSequence) {
                insertionIndex = index;
                break;
            }
        }
        if (insertionIndex < 0) {
            return;
        }
        state.conversation.removeIf(node ->
                node.sequence() >= startSequence && node.sequence() <= endSequence);
        LlmClient.Message summary = messageNode(replacement).message();
        state.conversation.add(insertionIndex, new SessionProjection.ConversationNode(
                replacement.sequence(), null, null, "summary", summary,
                SessionProjection.ConversationKind.SUMMARY));
    }

    private static void applySurface(MutableProjection state, SessionEvent event) {
        String operation = event.surface().op();
        switch (operation) {
            case "none" -> {
                return;
            }
            case "clear" -> state.surface.clear();
            case "append" -> state.surface.add(messageNode(event));
            case "replace" -> replace(state.surface, event);
            default -> throw new CorruptSessionException("unknown surface operation: " + operation);
        }
        state.historyVersion++;
        synchronizeContextSources(state, event);
    }

    private static void synchronizeContextSources(MutableProjection state, SessionEvent event) {
        JsonNode payload = event.payload();
        String scopeId = text(payload, "scopeId");
        if (payload != null && payload.path("contextScopeStart").asBoolean(false))
            scopeId = event.sessionId() + ":" + event.sequence();
        if (scopeId == null) scopeId = event.sessionId();
        ContextScopeState old = state.contextScopes.get(scopeId);
        List<ContextNode> sources = new ArrayList<>(old == null ? List.of() : old.sourceNodes());
        java.util.Set<Long> retained = new java.util.HashSet<>();
        state.surface.forEach(n -> retained.add(n.sequence()));
        sources.removeIf(node -> !retained.contains(node.id().sourceSequence()));
        if (!"clear".equals(event.surface().op()) && state.surface.stream().anyMatch(n -> n.sequence() == event.sequence())) {
            SourceKind kind = SourceKind.LEGACY_UNKNOWN;
            String declaredKind = text(payload, "sourceKind");
            if (declaredKind != null) {
                try { kind = SourceKind.valueOf(declaredKind); }
                catch (IllegalArgumentException e) { throw new CorruptSessionException("Invalid source kind"); }
            }
            ToolResultDescriptor descriptor = null;
            if (payload != null && payload.hasNonNull("toolResultDescriptor")) {
                try { descriptor = JSON.treeToValue(payload.get("toolResultDescriptor"), ToolResultDescriptor.class); }
                catch (Exception e) { throw new CorruptSessionException("Invalid result descriptor", e); }
            }
            ContextNode.SyntheticContext synthetic = null;
            if (payload != null && payload.hasNonNull("syntheticContext")) {
                try { synthetic = JSON.treeToValue(payload.get("syntheticContext"), ContextNode.SyntheticContext.class); }
                catch (Exception e) { throw new CorruptSessionException("Invalid synthetic provenance", e); }
            }
            ContextNode node = new ContextNode(NodeId.raw(scopeId, event.sequence()), messageNode(event).message(), kind,
                    text(payload, "contextExecutionId") == null ? text(payload, "executionId") : text(payload, "contextExecutionId"),
                    text(payload, "attemptId"), text(payload, "toolBatchId"), descriptor, synthetic);
            int insertion = 0;
            for (var surface : state.surface) {
                if (surface.sequence() == event.sequence()) break;
                if (sources.stream().anyMatch(n -> n.id().sourceSequence() == surface.sequence())) insertion++;
            }
            sources.add(insertion, node);
        }
        List<ContextEdit> edits = new ArrayList<>(old == null ? List.of() : old.activeEdits());
        java.util.Set<NodeId> retainedIds = new java.util.HashSet<>(); sources.forEach(n -> retainedIds.add(n.id()));
        boolean changed;
        do {
            java.util.Set<NodeId> reachable = new java.util.HashSet<>(retainedIds);
            edits.forEach(e -> e.replacementNodes().forEach(n -> reachable.add(n.id())));
            changed = edits.removeIf(e -> !retainedIds.containsAll(e.orderedSourceIds())
                    || e.dependencies().stream().anyMatch(d -> !reachable.contains(d.nodeId())));
        } while (changed);
        state.contextScopes.put(scopeId, new ContextScopeState(scopeId, old == null ? 1 : old.sourceRevision() + 1,
                old == null ? 0 : old.contextGeneration() + (edits.equals(old.activeEdits())
                        && !"replace".equals(event.surface().op()) && text(payload, "compactionId") == null ? 0 : 1), sources, edits));
        if ("clear".equals(event.surface().op())) {
            state.contextScopes.replaceAll((id, scope) -> new ContextScopeState(id, scope.sourceRevision() + 1,
                    scope.contextGeneration() + 1, List.of(), List.of()));
        }
    }

    private static SessionProjection.SurfaceNode messageNode(SessionEvent event) {
        JsonNode message = event.payload() == null ? null : event.payload().get("message");
        if (message == null || message.isNull()) {
            throw new CorruptSessionException("surface event has no message: " + event.type());
        }
        try {
            return new SessionProjection.SurfaceNode(
                    event.sequence(), JSON.treeToValue(message, LlmClient.Message.class));
        } catch (Exception e) {
            throw new CorruptSessionException("invalid message at sequence " + event.sequence(), e);
        }
    }

    private static void replace(List<SessionProjection.SurfaceNode> surface, SessionEvent event) {
        Long startSequence = event.surface().startSequence();
        Long endSequence = event.surface().endSequence();
        if (startSequence == null || endSequence == null || startSequence > endSequence) {
            throw new CorruptSessionException("invalid replacement range");
        }
        int start = indexOf(surface, startSequence);
        int end = indexOf(surface, endSequence);
        if (start < 0 || end < start) {
            throw new CorruptSessionException(
                    "replacement range is not present on active surface: " + startSequence + ".." + endSequence);
        }
        surface.subList(start, end + 1).clear();
        surface.add(start, messageNode(event));
    }

    private static int indexOf(List<SessionProjection.SurfaceNode> surface, long sequence) {
        for (int i = 0; i < surface.size(); i++) {
            if (surface.get(i).sequence() == sequence) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isMessageEvent(String type) {
        return SessionEvent.Types.SYSTEM_MESSAGE.equals(type)
                || SessionEvent.Types.USER_MESSAGE.equals(type)
                || SessionEvent.Types.ASSISTANT_MESSAGE.equals(type)
                || SessionEvent.Types.TOOL_RESULT.equals(type)
                || SessionEvent.Types.IMAGE_PRUNED.equals(type);
    }

    private static String requiredText(JsonNode payload, String field) {
        String value = text(payload, field);
        require(value, "missing payload field: " + field);
        return value;
    }

    private static String text(JsonNode payload, String field) {
        if (payload == null || payload.path(field).isMissingNode() || payload.path(field).isNull()) {
            return null;
        }
        String value = payload.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new CorruptSessionException(message);
        }
    }

    private static final class MutableProjection {
        private final List<SessionProjection.SurfaceNode> surface = new ArrayList<>();
        private final List<SessionProjection.ConversationNode> conversation = new ArrayList<>();
        private final Map<String, SessionProjection.OpenTurn> openTurns = new LinkedHashMap<>();
        private final Set<String> incompleteRequests = new LinkedHashSet<>();
        private final Map<String, SessionProjection.PendingToolInvocation> pendingTools = new LinkedHashMap<>();
        private final Map<String, SessionProjection.ExecutionEnvelope> executionEnvelopes = new LinkedHashMap<>();
        private final Map<String, List<SessionEvent>> compactions = new LinkedHashMap<>();
        private final Map<String, SessionEvent> compactionStarts = new LinkedHashMap<>();
        private final Map<String, List<SessionEvent>> requestAssistants = new LinkedHashMap<>();
        private final Map<String, SessionProjection.MeasuredUsageFact> requestUsages = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();
        private final Map<String, ContextScopeState> contextScopes = new LinkedHashMap<>();
        private final Map<String, String> committedOperationDigests = new LinkedHashMap<>();
        private int requiredSchemaVersion = 2;
        private long lastAppliedSequence = -1;
        private long historyVersion;
        private long compactionGeneration;
        private SessionProjection.MeasuredUsageFact lastCompletedUsage;
        private boolean cleanlyClosed;

        private MutableProjection(boolean cleanlyClosed) {
            this.cleanlyClosed = cleanlyClosed;
        }

        private MutableProjection(SessionProjection checkpoint) {
            surface.addAll(checkpoint.activeSurface());
            conversation.addAll(checkpoint.topLevelConversation());
            openTurns.putAll(checkpoint.openTurns());
            incompleteRequests.addAll(checkpoint.incompleteRequestIds());
            pendingTools.putAll(checkpoint.pendingTools());
            executionEnvelopes.putAll(checkpoint.executionEnvelopes());
            warnings.addAll(checkpoint.warnings());
            lastAppliedSequence = checkpoint.lastAppliedSequence();
            historyVersion = checkpoint.historyVersion();
            compactionGeneration = checkpoint.compactionGeneration();
            lastCompletedUsage = checkpoint.lastCompletedUsage();
            cleanlyClosed = checkpoint.cleanlyClosed();
            contextScopes.putAll(checkpoint.contextScopes());
            committedOperationDigests.putAll(checkpoint.committedOperationDigests());
            requiredSchemaVersion = checkpoint.requiredSchemaVersion();
        }

        private SessionProjection freeze() {
            return new SessionProjection(surface, conversation, openTurns,
                    lastAppliedSequence, historyVersion, compactionGeneration, lastCompletedUsage,
                    incompleteRequests, pendingTools, executionEnvelopes, cleanlyClosed, warnings,
                    contextScopes, committedOperationDigests, requiredSchemaVersion);
        }
    }

    public static class CorruptSessionException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public CorruptSessionException(String message) {
            super(message);
        }

        public CorruptSessionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class UnsupportedSessionEventException extends CorruptSessionException {
        private static final long serialVersionUID = 1L;

        public UnsupportedSessionEventException(String message) {
            super(message);
        }
    }
}
