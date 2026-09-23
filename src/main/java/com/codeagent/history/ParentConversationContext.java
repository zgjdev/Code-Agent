package com.codeagent.history;

import com.codeagent.llm.LlmClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared owner of the parent session provider-facing message list.
 *
 * <p>The durable {@link SessionProjection} remains authoritative when a session is attached.
 * ReAct and Plan share this object so cross-mode mutations are observed through one list identity.
 */
public final class ParentConversationContext {
    private final List<LlmClient.Message> providerMessages = new ArrayList<>();
    private SessionStore.SessionHandle sessionHandle;

    public ParentConversationContext(LlmClient.Message initialSystem) {
        if (initialSystem != null) {
            providerMessages.add(initialSystem);
        }
    }

    /** Stable mutable list identity consumed by the parent ReAct agent. */
    public List<LlmClient.Message> providerMessages() {
        return providerMessages;
    }

    public synchronized SessionStore.SessionHandle sessionHandle() {
        return sessionHandle;
    }

    public synchronized void setSessionHandle(SessionStore.SessionHandle handle) {
        this.sessionHandle = handle;
    }

    public synchronized SessionProjection projection() {
        return sessionHandle == null ? null : sessionHandle.projection();
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
    }

    public synchronized SessionEvent append(SessionEventDraft draft) throws IOException {
        if (sessionHandle == null) {
            throw new IllegalStateException("parent session is not attached");
        }
        return sessionHandle.append(Objects.requireNonNull(draft, "draft"));
    }
}
