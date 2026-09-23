package com.codeagent.history;

import com.codeagent.llm.LlmClient;
import com.codeagent.memory.AutoCompactionManager;
import com.codeagent.memory.TokenBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared owner of the parent-session provider-facing message list.
 *
 * <p>The durable {@link SessionProjection} remains authoritative when a session is attached.
 * ReAct and Plan share this object so cross-mode mutations are observed through one list identity.
 */
public final class ParentConversationContext {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<LlmClient.Message> providerMessages = new ArrayList<>();
    private final AutoCompactionManager compactionManager;
    private SessionStore.SessionHandle sessionHandle;
    private long localHistoryVersion;
    private long localCompactionGeneration;

    public ParentConversationContext(LlmClient.Message initialSystem, LlmClient llmClient) {
        if (initialSystem != null) {
            providerMessages.add(initialSystem);
            localHistoryVersion = 1L;
        }
        this.compactionManager = new AutoCompactionManager(llmClient);
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
        compactionManager.setLlmClient(llmClient);
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

    public synchronized ParentCompactionResult compactIfNeeded(int triggerTokens,
                                                               long effectiveTokens,
                                                               String mode,
                                                               String actor,
                                                               String source) throws IOException {
        if (effectiveTokens < triggerTokens) {
            return ParentCompactionResult.notCompacted(
                    TokenBudget.estimateMessagesTokens(providerMessages));
        }
        List<LlmClient.Message> candidate = new ArrayList<>(providerMessages);
        AutoCompactionManager.Result result;
        try {
            result = compactionManager.compactIfNeeded(candidate, triggerTokens, effectiveTokens);
        } catch (RuntimeException e) {
            throw new IOException("parent conversation compaction failed", e);
        }
        if (!result.compacted()) {
            return ParentCompactionResult.notCompacted(
                    TokenBudget.estimateMessagesTokens(providerMessages));
        }
        long beforeTokens = TokenBudget.estimateMessagesTokens(providerMessages);
        commitCompaction(candidate, mode, actor, source);
        long afterTokens = TokenBudget.estimateMessagesTokens(providerMessages);
        return new ParentCompactionResult(true, result.strategy(), beforeTokens, afterTokens);
    }

    public synchronized ParentCompactionResult compactNow(String mode,
                                                          String actor,
                                                          String source) throws IOException {
        List<LlmClient.Message> candidate = new ArrayList<>(providerMessages);
        AutoCompactionManager.Result result;
        try {
            result = compactionManager.compactNow(candidate);
        } catch (RuntimeException e) {
            throw new IOException("parent conversation compaction failed", e);
        }
        if (!result.compacted()) {
            return ParentCompactionResult.notCompacted(
                    TokenBudget.estimateMessagesTokens(providerMessages));
        }
        long beforeTokens = TokenBudget.estimateMessagesTokens(providerMessages);
        commitCompaction(candidate, mode, actor, source);
        long afterTokens = TokenBudget.estimateMessagesTokens(providerMessages);
        return new ParentCompactionResult(true, result.strategy(), beforeTokens, afterTokens);
    }

    public synchronized void commitPreparedCompaction(List<LlmClient.Message> candidate,
                                                       String mode,
                                                       String actor,
                                                       String source) throws IOException {
        commitCompaction(candidate, mode, actor, source);
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
