package com.codeagent.agent;

import com.codeagent.context.ContextAutoCompactor;
import com.codeagent.context.ContextCollapseManager;
import com.codeagent.context.ContextEdit;
import com.codeagent.context.ContextEditBatch;
import com.codeagent.context.ContextEditReplay;
import com.codeagent.context.ContextEditSink;
import com.codeagent.context.ContextNode;
import com.codeagent.context.ContextProfile;
import com.codeagent.context.ContextScopeState;
import com.codeagent.context.ContextTokenTracker;
import com.codeagent.context.ContextWindowManager;
import com.codeagent.context.ContextWindowState;
import com.codeagent.context.MemoryContextEditSink;
import com.codeagent.context.NodeId;
import com.codeagent.context.RequestPurpose;
import com.codeagent.context.RequestSnapshot;
import com.codeagent.context.SourceKind;
import com.codeagent.context.StructuredContextSummarizer;
import com.codeagent.context.ToolResultDescriptor;
import com.codeagent.history.SessionContextEditSink;
import com.codeagent.history.SessionStore;
import com.codeagent.llm.LlmClient;
import com.codeagent.llm.ContextWindowExceededException;
import com.codeagent.prompt.PromptAssembler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Owns one execution's source identities, projected context and summary allowance. */
final class BranchRequestContext {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String executionId = UUID.randomUUID().toString();
    private final SessionStore.SessionHandle child;
    private final ContextWindowState.Options options;
    private ContextWindowState state;
    private long localSequence;
    private String toolBatchId;

    BranchRequestContext(SessionStore.SessionHandle child, ContextProfile profile) {
        this.child = child;
        this.options = ContextWindowState.Options.defaults(profile, true);
    }

    void describe(ObjectNode payload, SourceKind kind, ToolResultDescriptor descriptor) {
        payload.put("sourceKind", kind.name()).put("contextExecutionId", executionId)
                .put("attemptId", executionId);
        if (state == null && child != null) payload.put("contextScopeStart", true);
        else if (child != null) payload.put("scopeId", state.scopeId());
        if (kind == SourceKind.TOOL_CALL) toolBatchId = UUID.randomUUID().toString();
        if ((kind == SourceKind.TOOL_CALL || kind == SourceKind.TOOL_RESULT) && toolBatchId != null)
            payload.put("toolBatchId", toolBatchId);
        if (descriptor != null) payload.set("toolResultDescriptor", JSON.valueToTree(descriptor));
    }

    void append(LlmClient.Message message, SourceKind kind, ToolResultDescriptor descriptor, Long sequence) {
        if (state == null) {
            String scopeId = child == null ? executionId : child.sessionId() + ":" + sequence;
            state = new ContextWindowState(scopeId, options);
        }
        if (child == null && kind == SourceKind.TOOL_CALL) toolBatchId = UUID.randomUUID().toString();
        String batchId = kind == SourceKind.TOOL_CALL || kind == SourceKind.TOOL_RESULT ? toolBatchId : null;
        state.append(new ContextNode(NodeId.raw(state.scopeId(), sequence == null ? ++localSequence : sequence),
                message, kind, executionId, executionId, batchId, descriptor));
    }

    void appendRecoveryMessage(LlmClient.Message message) {
        state.append(new ContextNode(NodeId.request(executionId, Math.toIntExact(++localSequence)),
                message, SourceKind.RECOVERY_CONTROL, executionId, executionId, null, null));
    }

    ContextTokenTracker tracker() { return state.tracker(); }

    record Chat(LlmClient.ChatResponse response, RequestSnapshot snapshot) {}

    Chat chat(LlmClient client, PromptAssembler prompts, List<LlmClient.Tool> tools,
              RequestPurpose purpose, AgentBudget budget, LlmClient.StreamListener listener) throws IOException {
        var request = prepare(client, prompts, tools, purpose, budget, false);
        if (!request.sendable()) throw new IOException("Protected context exceeds the request budget");
        try {
            return new Chat(client.chat(request.messages(), request.tools(), listener), request.snapshot());
        } catch (ContextWindowExceededException overflow) {
            var retry = prepare(client, prompts, request.tools(), purpose, budget, true);
            if (!retry.sendable() || retry.snapshot().surfaceFingerprint().equals(request.snapshot().surfaceFingerprint())
                    || retry.snapshot().estimatedRequestTokens() >= request.snapshot().estimatedRequestTokens()) {
                throw overflow;
            }
            return new Chat(client.chat(retry.messages(), retry.tools(), listener), retry.snapshot());
        }
    }

    ContextWindowManager.Result prepare(LlmClient client, PromptAssembler prompts, List<LlmClient.Tool> tools,
                                        RequestPurpose purpose, AgentBudget budget, boolean overflow) throws IOException {
        ContextEditSink sink;
        if (child == null) {
            sink = new MemoryContextEditSink(state);
        } else {
            var durable = new SessionContextEditSink(child, state::protection);
            sink = batch -> {
                var result = durable.commit(batch);
                var scope = child.projection().contextScopes().get(state.scopeId());
                if (scope.contextGeneration() > state.contextGeneration()) {
                    state.publishCommittedEdits(scope.contextGeneration(), scope.activeEdits());
                }
                return result;
            };
        }
        var summarizer = new StructuredContextSummarizer(client, prompts,
                () -> budget.check() == AgentBudget.ExitReason.WITHIN_BUDGET,
                response -> {
                    if (response != null) budget.recordTokens(response.inputTokens(),
                            response.outputTokens(), response.cachedInputTokens());
                });
        var summaryBudget = new com.codeagent.context.SummaryCallBudget(16);
            return new ContextWindowManager().prepare(client, tools, purpose, overflow, options, state.tracker(),
                    state::snapshot, sink, new ContextWindowManager.SummaryLayers() {
                        @Override public Optional<ContextEdit> collapse(ContextWindowState.Snapshot snapshot,
                                                                       List<ContextNode> view) {
                            if (budget.check() != AgentBudget.ExitReason.WITHIN_BUDGET) return Optional.empty();
                            return new ContextCollapseManager(summarizer).propose(snapshot, options, summaryBudget);
                        }

                        @Override public boolean auto(ContextWindowState.Snapshot snapshot,
                                                      List<ContextNode> view) throws IOException {
                            if (budget.check() != AgentBudget.ExitReason.WITHIN_BUDGET) return false;
                            var candidate = new ContextAutoCompactor(summarizer).propose(snapshot, view,
                                    options.triggerTokens(), 3, summaryBudget);
                            if (candidate.isEmpty()) return false;
                            var scope = new ContextScopeState(snapshot.scopeId(), snapshot.sourceRevision(),
                                    snapshot.contextGeneration(), snapshot.sourceNodes(), snapshot.activeEdits());
                            var batch = new ContextEditBatch(UUID.randomUUID().toString(), snapshot.scopeId(),
                                    snapshot.sourceRevision(), snapshot.contextGeneration(), snapshot.protection().digest(),
                                    ContextEditReplay.baseDigest(scope), List.of(candidate.get()));
                            return sink.commit(batch) != ContextEditSink.CommitResult.STALE;
                        }
                    });
    }

    static SourceKind kind(LlmClient.Message message, String source) {
        if ("task_input".equals(source)) return SourceKind.REAL_USER;
        if ("budget_finalization".equals(source)) return SourceKind.RECOVERY_CONTROL;
        return switch (message.role()) {
            case "system" -> SourceKind.SYSTEM;
            case "assistant" -> message.toolCalls() == null || message.toolCalls().isEmpty()
                    ? SourceKind.ASSISTANT : SourceKind.TOOL_CALL;
            case "tool" -> SourceKind.TOOL_RESULT;
            default -> SourceKind.SYNTHETIC;
        };
    }
}
