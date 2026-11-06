package com.codeagent.history;

import com.codeagent.context.ContextDigests;
import com.codeagent.context.ContextEdit;
import com.codeagent.context.ContextNode;
import com.codeagent.context.ContextWindowState;
import com.codeagent.context.NodeId;
import com.codeagent.context.SourceKind;
import com.codeagent.context.ContextAutoCompactor;
import com.codeagent.context.ContextEditReplay;
import com.codeagent.context.ContextProfile;
import com.codeagent.context.ProtectionSet;
import com.codeagent.context.StructuredContextSummarizer;
import com.codeagent.context.SummaryCallBudget;
import com.codeagent.llm.LlmClient;
import com.codeagent.memory.AutoCompactionManager;
import com.codeagent.memory.TokenBudget;
import com.codeagent.runtime.CancellationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Shared owner of the parent-session provider-facing message list.
 *
 * <p>The durable {@link SessionProjection} remains authoritative when a session is attached.
 * ReAct and Plan share this object so cross-mode mutations are observed through one list identity.
 */
public final class ParentConversationContext {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<LlmClient.Message> providerMessages = new ArrayList<>();
    private AutoCompactionManager compactionManager;
    private LlmClient llmClient;
    private SessionStore.SessionHandle sessionHandle;
    private long localHistoryVersion;
    private long localCompactionGeneration;

    public ParentConversationContext(LlmClient.Message initialSystem, LlmClient llmClient) {
        if (initialSystem != null) {
            providerMessages.add(initialSystem);
            localHistoryVersion = 1L;
        }
        this.compactionManager = new AutoCompactionManager(llmClient);
        this.llmClient = llmClient;
    }

    /** Backward-compatible constructor for tests and callers that do not compact. */
    public ParentConversationContext(LlmClient.Message initialSystem) {
        this(initialSystem, null);
    }

    /** Stable mutable list identity consumed by the parent ReAct agent. */
    public List<LlmClient.Message> providerMessages() {
        return providerMessages;
    }

    public synchronized void setLlmClient(LlmClient llmClient) {
        compactionManager = new AutoCompactionManager(llmClient);
        this.llmClient = llmClient;
    }

    public synchronized SessionStore.SessionHandle sessionHandle() {
        return sessionHandle;
    }

    public synchronized void setSessionHandle(SessionStore.SessionHandle handle) {
        this.sessionHandle = handle;
        if (handle != null) {
            localHistoryVersion = handle.projection().historyVersion();
            localCompactionGeneration = handle.projection().compactionGeneration();
        }
    }

    public synchronized SessionProjection projection() {
        return sessionHandle == null ? null : sessionHandle.projection();
    }

    public synchronized long historyVersion() {
        SessionProjection projection = projection();
        return projection == null ? localHistoryVersion : projection.historyVersion();
    }

    public synchronized long compactionGeneration() {
        SessionProjection projection = projection();
        return projection == null ? localCompactionGeneration : projection.compactionGeneration();
    }

    public synchronized List<LlmClient.Message> conversationMessages() {
        SessionProjection projection = projection();
        return projection == null ? List.of() : projection.conversationMessages();
    }

    public synchronized List<SessionProjection.ConversationNode> conversationNodes() {
        SessionProjection projection = projection();
        return projection == null ? List.of() : projection.topLevelConversation();
    }

    public synchronized void synchronizeProviderFromProjection() {
        SessionProjection projection = projection();
        if (projection == null) {
            return;
        }
        providerMessages.clear();
        providerMessages.addAll(projection.messages());
        localHistoryVersion = projection.historyVersion();
        localCompactionGeneration = projection.compactionGeneration();
    }

    public synchronized SessionEvent append(SessionEventDraft draft) throws IOException {
        if (sessionHandle == null) {
            throw new IllegalStateException("parent session is not attached");
        }
        SessionEvent event = sessionHandle.append(Objects.requireNonNull(draft, "draft"));
        localHistoryVersion = sessionHandle.projection().historyVersion();
        localCompactionGeneration = sessionHandle.projection().compactionGeneration();
        return event;
    }

    /** Explicit owner, raw semantic input, and provider parts remain separate; never uses ThreadLocal. */
    public synchronized SessionEvent appendExecutionMessage(String executionId, LlmClient.Message message,
            String semanticContent, String actor, String source) throws IOException {
        Objects.requireNonNull(message); Objects.requireNonNull(semanticContent);
        if (sessionHandle == null) throw new IllegalStateException("parent session is not attached");
        boolean user = "user".equals(message.role());
        if (!user && !"assistant".equals(message.role()) || message.toolCallId() != null
                || message.toolCalls() != null && !message.toolCalls().isEmpty()
                || executionId == null || actor == null || source == null)
            throw new IllegalArgumentException("Only explicit top-level user/final assistant messages are supported");
        var envelope = sessionHandle.projection().executionEnvelopes().get(executionId);
        if (envelope == null) throw new IllegalStateException("Execution must start before its messages");
        Long existing = user ? envelope.userSequence() : envelope.assistantSequence();
        if (existing != null) {
            var event = sessionHandle.readAll().stream().filter(e -> e.sequence() == existing).findFirst().orElseThrow();
            if (!JSON.valueToTree(message).equals(event.payload().get("message"))
                    || !semanticContent.equals(event.payload().path("conversation").path("content").asText())
                    || !actor.equals(event.actor()) || !source.equals(event.source()))
                throw new IllegalStateException("Execution message already has different durable content");
            return event;
        }
        if (envelope.ended()) throw new IllegalStateException("Cannot append to ended execution");
        ObjectNode payload = JSON.createObjectNode().put("executionId", executionId);
        payload.put("sourceKind", user ? SourceKind.REAL_USER.name() : SourceKind.ASSISTANT.name());
        payload.put("contextExecutionId", executionId);
        payload.set("message", JSON.valueToTree(message));
        payload.putObject("conversation").put("executionId", executionId).put("role", message.role())
                .put("content", semanticContent).put("mode", "react");
        var event = append(new SessionEventDraft(user ? SessionEvent.Types.USER_MESSAGE : SessionEvent.Types.ASSISTANT_MESSAGE,
                "react", actor, source, false, SessionEvent.SurfaceOperation.append(), payload));
        synchronizeProviderFromProjection();
        return event;
    }

    public ParentCompactionResult compactIfNeeded(int triggerTokens,
                                                               long effectiveTokens,
                                                               String mode,
                                                               String actor,
                                                               String source) throws IOException {
        return compact(false, triggerTokens, effectiveTokens, mode, actor, source);
    }

    public ParentCompactionResult compactNow(String mode,
                                                          String actor,
                                                          String source) throws IOException {
        return compact(true, 0, 0, mode, actor, source);
    }

    private ParentCompactionResult compact(boolean manual, int triggerTokens, long effectiveTokens,
                                           String mode, String actor, String source) throws IOException {
        if (com.codeagent.config.ContextManagementConfig.load().enabled() && sessionHandle() != null) {
            return compactLayered(manual, triggerTokens, effectiveTokens, mode, actor, source);
        }
        CompactionInput input;
        synchronized (this) {
            if (!manual && effectiveTokens < triggerTokens) {
                return ParentCompactionResult.notCompacted(TokenBudget.estimateMessagesTokens(providerMessages));
            }
            input = new CompactionInput(sessionHandle, compactionManager, historyVersion(),
                    compactionGeneration(), List.copyOf(providerMessages));
        }
        List<LlmClient.Message> candidate = new ArrayList<>(input.messages());
        AutoCompactionManager.Result result;
        try {
            result = manual ? input.manager().compactNow(candidate)
                    : input.manager().compactIfNeeded(candidate, triggerTokens, effectiveTokens);
        } catch (RuntimeException e) {
            throw new IOException("parent conversation compaction failed", e);
        }
        synchronized (this) {
            long beforeTokens = TokenBudget.estimateMessagesTokens(providerMessages);
            if (!result.compacted() || input.handle() != sessionHandle || input.manager() != compactionManager
                    || input.version() != historyVersion() || input.generation() != compactionGeneration()
                    || !input.messages().equals(providerMessages)) {
                return ParentCompactionResult.notCompacted(beforeTokens);
            }
            commitCompaction(candidate, mode, actor, source);
            return new ParentCompactionResult(true, result.strategy(), beforeTokens,
                    TokenBudget.estimateMessagesTokens(providerMessages));
        }
    }

    private record CompactionInput(SessionStore.SessionHandle handle, AutoCompactionManager manager,
                                   long version, long generation, List<LlmClient.Message> messages) {}

    private ParentCompactionResult compactLayered(boolean manual, int triggerTokens, long effectiveTokens,
                                                  String mode, String actor, String source) throws IOException {
        LlmClient client;
        synchronized (this) { client = llmClient; }
        if (client == null) throw new IOException("Parent compaction client unavailable");
        Supplier<ContextWindowState.Snapshot> refresh = () -> {
            synchronized (this) {
                var scope = sessionHandle.projection().contextScopes().get(sessionHandle.sessionId());
                var protection = ProtectionSet.derive(scope.sourceNodes(), ProtectionSet.empty(), 2);
                return new ContextWindowState.Snapshot(scope.scopeId(), scope.sourceRevision(), scope.contextGeneration(),
                        scope.sourceNodes(), scope.activeEdits(), protection);
            }
        };
        var snapshot = refresh.get();
        var scope = new com.codeagent.context.ContextScopeState(snapshot.scopeId(), snapshot.sourceRevision(),
                snapshot.contextGeneration(), snapshot.sourceNodes(), snapshot.activeEdits());
        var view = ContextEditReplay.project(scope, snapshot.protection());
        long before = TokenBudget.estimateMessagesTokens(view.stream().map(ContextNode::message).toList());
        if (!manual && effectiveTokens < triggerTokens) return ParentCompactionResult.notCompacted(before);
        var proposal = new ContextAutoCompactor(new StructuredContextSummarizer(client)).propose(snapshot, view,
                ContextProfile.from(client).compressionTriggerTokens(), manual ? 1 : 3, new SummaryCallBudget(16));
        if (proposal.isEmpty() || !commitLayeredCompaction(snapshot, proposal.get(), refresh, mode, actor, source)) {
            return ParentCompactionResult.notCompacted(before);
        }
        return new ParentCompactionResult(true, AutoCompactionManager.Strategy.FULL_SUMMARY, before,
                TokenBudget.estimateMessagesTokens(providerMessages()));
    }

    public synchronized void commitPreparedCompaction(List<LlmClient.Message> candidate,
                                                       String mode,
                                                       String actor,
                                                       String source) throws IOException {
        commitCompaction(candidate, mode, actor, source);
    }

    /** Commit a bounded global summary prepared outside session locks. */
    public synchronized boolean commitLayeredCompaction(ContextWindowState.Snapshot expected,
                                                        ContextEdit edit,
                                                        Supplier<ContextWindowState.Snapshot> refresh,
                                                        String mode, String actor, String source) throws IOException {
        var handle = sessionHandle;
        if (handle == null || !handle.sessionId().equals(expected.scopeId()) || CancellationContext.isCancelled()) {
            return false;
        }
        // Evaluate host protection before taking the handle monitor, following context → handle lock order.
        var projection = handle.projection();
        var current = refresh.get();
        if (!current.scopeId().equals(expected.scopeId())
                || current.sourceRevision() != expected.sourceRevision()
                || current.contextGeneration() != expected.contextGeneration()
                || !current.protection().digest().equals(expected.protection().digest())
                || !current.sourceNodes().equals(expected.sourceNodes())) return false;

        if (edit.layer() != ContextEdit.Layer.AUTO || edit.kind() != ContextEdit.Kind.REPLACE_SPAN
                || edit.replacementNodes().size() != 1 || !edit.dependencies().isEmpty()) {
            throw new IllegalArgumentException("invalid global compaction candidate");
        }
        ContextNode replacement = edit.replacementNodes().get(0);
        if (replacement.sourceKind() != SourceKind.GLOBAL_SUMMARY || !"assistant".equals(replacement.message().role())
                || replacement.message().toolCallId() != null || replacement.message().toolCalls() != null
                && !replacement.message().toolCalls().isEmpty()) {
            throw new IllegalArgumentException("invalid global compaction replacement");
        }
        List<NodeId> ids = edit.orderedSourceIds();
        List<ContextNode> raw = current.sourceNodes().stream().filter(node -> node.id().kind() == NodeId.Kind.RAW).toList();
        int start = 0;
        while (start < raw.size() && "system".equals(raw.get(start).message().role())) start++;
        int end = start + ids.size();
        if (end >= raw.size() || !raw.subList(start, end).stream().map(ContextNode::id).toList().equals(ids)
                || !ContextDigests.sourceDigest(raw.subList(start, end)).equals(edit.sourceDigest())
                || ids.stream().anyMatch(current.protection().protectedNodeIds()::contains)) {
            throw new IllegalArgumentException("global compaction does not match eligible prefix");
        }
        var coveredSequences = new HashSet<>(ids.stream().map(NodeId::sourceSequence).toList());
        var coveredConversation = projection.topLevelConversation().stream()
                .takeWhile(node -> coveredSequences.contains(node.sequence()))
                .map(SessionProjection.ConversationNode::sequence).toList();
        String transaction = UUID.randomUUID().toString();
        ObjectNode startPayload = JSON.createObjectNode().put("compactionId", transaction)
                .put("scopeId", expected.scopeId()).put("expectedSourceRevision", expected.sourceRevision())
                .put("expectedContextGeneration", expected.contextGeneration())
                .put("expectedProtectionDigest", expected.protection().digest()).put("sourceDigest", edit.sourceDigest());
        startPayload.set("orderedSourceIds", JSON.valueToTree(ids));
        startPayload.set("coveredConversationNodeIds", JSON.valueToTree(coveredConversation));
        startPayload.set("retiredEditIds", JSON.valueToTree(edit.retiredEditIds()));
        ObjectNode summary = JSON.createObjectNode().put("compactionId", transaction)
                .put("sourceKind", SourceKind.GLOBAL_SUMMARY.name());
        summary.set("message", JSON.valueToTree(replacement.message()));
        ObjectNode endPayload = JSON.createObjectNode().put("compactionId", transaction).put("status", "completed")
                .put("transactionDigest", ContextDigests.canonicalDigest(List.of(startPayload, summary)));

        synchronized (handle) {
            if (projection != handle.projection() || CancellationContext.isCancelled()) return false;
            append(new SessionEventDraft(SessionEvent.Types.COMPACTION_START, mode, actor, source, false,
                    SessionEvent.SurfaceOperation.none(), startPayload, 3));
            append(new SessionEventDraft(SessionEvent.Types.ASSISTANT_MESSAGE, mode, actor, source, false,
                    SessionEvent.SurfaceOperation.none(), summary, 3));
            append(new SessionEventDraft(SessionEvent.Types.COMPACTION_END, mode, actor, source, false,
                    SessionEvent.SurfaceOperation.none(), endPayload, 3));
        }
        synchronizeProviderFromProjection();
        return true;
    }

    private void commitCompaction(List<LlmClient.Message> candidate,
                                  String mode,
                                  String actor,
                                  String source) throws IOException {
        if (candidate.equals(providerMessages)) {
            return;
        }
        if (sessionHandle == null) {
            providerMessages.clear();
            providerMessages.addAll(candidate);
            localHistoryVersion++;
            localCompactionGeneration++;
            return;
        }

        int commonSuffix = commonSuffixLength(providerMessages, candidate);
        int removedEndIndex = providerMessages.size() - commonSuffix - 1;
        if (providerMessages.size() < 2 || candidate.size() < commonSuffix + 3
                || removedEndIndex < 1) {
            throw new IOException("unsupported parent compaction shape");
        }

        List<SessionProjection.SurfaceNode> nodes = sessionHandle.projection().activeSurface();
        if (nodes.size() != providerMessages.size()) {
            throw new IOException("parent provider surface is out of sync with session projection");
        }
        long startSequence = nodes.get(1).sequence();
        long endSequence = nodes.get(removedEndIndex).sequence();
        String compactionId = UUID.randomUUID().toString();
        String safeMode = mode == null || mode.isBlank() ? "system" : mode;
        String safeActor = actor == null || actor.isBlank() ? "parent-context" : actor;
        String safeSource = source == null || source.isBlank() ? "automatic" : source;

        ObjectNode start = JSON.createObjectNode()
                .put("compactionId", compactionId)
                .put("source", safeSource)
                .put("beforeTokens", TokenBudget.estimateMessagesTokens(providerMessages));
        append(new SessionEventDraft(SessionEvent.Types.COMPACTION_START,
                safeMode, safeActor, safeSource, false,
                SessionEvent.SurfaceOperation.none(), start));

        ObjectNode summary = JSON.createObjectNode()
                .put("compactionId", compactionId)
                .put("afterTokens", TokenBudget.estimateMessagesTokens(candidate));
        append(new SessionEventDraft(SessionEvent.Types.COMPACTION_SUMMARY,
                safeMode, safeActor, safeSource, false,
                SessionEvent.SurfaceOperation.none(), summary));

        appendCompactionMessage(candidate.get(1), SessionEvent.Types.USER_MESSAGE,
                SessionEvent.SurfaceOperation.replace(startSequence, endSequence),
                compactionId, safeMode, safeActor, safeSource);
        appendCompactionMessage(candidate.get(2), SessionEvent.Types.ASSISTANT_MESSAGE,
                SessionEvent.SurfaceOperation.append(),
                compactionId, safeMode, safeActor, safeSource);

        ObjectNode end = JSON.createObjectNode()
                .put("compactionId", compactionId)
                .put("status", "completed");
        append(new SessionEventDraft(SessionEvent.Types.COMPACTION_END,
                safeMode, safeActor, safeSource, false,
                SessionEvent.SurfaceOperation.none(), end));

        synchronizeProviderFromProjection();
    }

    private void appendCompactionMessage(LlmClient.Message message,
                                         String type,
                                         SessionEvent.SurfaceOperation operation,
                                         String compactionId,
                                         String mode,
                                         String actor,
                                         String source) throws IOException {
        ObjectNode payload = JSON.createObjectNode().put("compactionId", compactionId);
        payload.set("message", JSON.valueToTree(message));
        append(new SessionEventDraft(type, mode, actor, source, false, operation, payload));
    }

    private static int commonSuffixLength(List<LlmClient.Message> before,
                                          List<LlmClient.Message> after) {
        int count = 0;
        while (count < before.size() && count < after.size()
                && before.get(before.size() - 1 - count)
                .equals(after.get(after.size() - 1 - count))) {
            count++;
        }
        return count;
    }

    public record ParentCompactionResult(boolean compacted,
                                         AutoCompactionManager.Strategy strategy,
                                         long beforeTokens,
                                         long afterTokens) {
        static ParentCompactionResult notCompacted(long tokens) {
            return new ParentCompactionResult(
                    false, AutoCompactionManager.Strategy.NONE, tokens, tokens);
        }
    }
}
