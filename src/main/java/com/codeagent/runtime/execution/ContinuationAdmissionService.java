package com.codeagent.runtime.execution;

import com.codeagent.agent.ExecutionMode;
import com.codeagent.agent.RecoveryRequestAugmenter;
import com.codeagent.agent.RoutingSource;
import com.codeagent.llm.LlmClient;
import com.codeagent.model.recovery.*;
import java.util.Objects;

/** Local control receipts are durable before release. This class never runs the selected task. */
public final class ContinuationAdmissionService {
    private final RuntimeExecutionStore store;
    private final ContinuationCapability capability;
    private final ExecutionFinalizer finalizer;
    public ContinuationAdmissionService(RuntimeExecutionStore store, ContinuationCapability capability) {
        this.store = Objects.requireNonNull(store); this.capability = Objects.requireNonNull(capability);
        this.finalizer = new ExecutionFinalizer(store);
    }

    public ContinuationIntent classify(ContinuationCapability.Scope scope, ContinuationIntentRouter router,
            RecoveryContextSnapshot snapshot, RecoveryContextBudget budget) throws Exception {
        var request = capability.requireRunning(scope);
        requireSnapshot(scope, request, snapshot);
        if (request.requestSemantics() != null) return committedIntent(request);
        var envelope = scope.context().session().projection().executionEnvelopes().get(request.id());
        if (envelope != null && (envelope.userSequence() != null || envelope.assistantSequence() != null)
                || request.continuationRequestId() != null)
            throw new RecoveryPreparationDeferredException("Unclassified historical execution requires an explicit continuation ticket");
        var intent = router.route(request, scope.context().agent().getParentConversationContext().conversationNodes(), snapshot, budget);
        capability.requireRunning(scope);
        if (intent.kind() == ContinuationIntent.Kind.CONTINUE) capability.prepare(scope, intent.targetExecutionId(), intent.targetGeneration());
        else store.commitRequestSemantics(scope.claim(), intent.semantics());
        return intent;
    }

    /** Explicit /plan resume may supply a locally validated exact target via the same admission path. */
    public Receipt accept(ContinuationCapability.Scope scope, ContinuationIntent intent, LlmClient.Message currentInputMessage) throws Exception {
        if (intent.kind() == ContinuationIntent.Kind.NEW_TURN) throw new IllegalArgumentException("New turns require an answer adapter");
        var request = capability.verify(scope);
        if (!request.terminal()) {
            request = intent.kind() == ContinuationIntent.Kind.CONTINUE
                    ? capability.prepare(scope, intent.targetExecutionId(), intent.targetGeneration())
                    : store.commitRequestSemantics(scope.claim(), RequestSemantics.CLARIFY);
        }
        if (!committedIntent(request).equals(intent)) throw new IllegalStateException("Intent differs from durable request");
        String ack = intent.kind() == ContinuationIntent.Kind.CONTINUE
                ? "已接受继续请求；原任务将在调度后继续执行。"
                : "请明确要继续的任务；原任务仍在等待，不会自动执行。";
        if (!request.terminal()) {
            finalizer.ensureStarted(request, scope.context().session());
            var parent = scope.context().agent().getParentConversationContext();
            parent.appendExecutionMessage(request.id(), requireUser(currentInputMessage), request.submittedInput(),
                    RecoveryRequestAugmenter.CONTROL_ACTOR, RecoveryRequestAugmenter.CONTROL_SOURCE);
            parent.appendExecutionMessage(request.id(), LlmClient.Message.assistant(ack), ack,
                    RecoveryRequestAugmenter.CONTROL_ACTOR, RecoveryRequestAugmenter.CONTROL_SOURCE);
            request = finalizer.finish(scope.claim(), new TopLevelExecutionResult(ExecutionMode.REACT,
                    RoutingSource.CONTINUATION_CONTROL, ExecutionOutcome.SUCCEEDED, ack, null), scope.context().session());
        }
        var release = intent.kind() == ContinuationIntent.Kind.CONTINUE ? capability.releaseCompleted(scope) : ContinuationReleaseResult.NOT_READY;
        return new Receipt(request, release);
    }

    /** Call before capturing the fresh status snapshot; preserves current resolved attachment parts. */
    public long persistStatusInput(ContinuationCapability.Scope scope, LlmClient.Message currentInputMessage) throws Exception {
        var request = capability.requireRunning(scope);
        if (request.requestSemantics() != RequestSemantics.STATUS_QUESTION)
            throw new IllegalStateException("Status semantics must be committed before user persistence");
        finalizer.ensureStarted(request, scope.context().session());
        return scope.context().agent().getParentConversationContext().appendExecutionMessage(request.id(), requireUser(currentInputMessage),
                request.submittedInput(), "agent", "agent").sequence();
    }

    /** Snapshot must be freshly captured after persistStatusInput, from the same actual source view. */
    public RecoveryRequestContext statusContext(ContinuationCapability.Scope scope, RecoveryContextSnapshot snapshot) throws Exception {
        var request = capability.requireRunning(scope);
        requireSnapshot(scope, request, snapshot);
        if (request.requestSemantics() != RequestSemantics.STATUS_QUESTION) throw new IllegalStateException("Not a status request");
        var envelope = scope.context().session().projection().executionEnvelopes().get(request.id());
        if (envelope == null || envelope.userSequence() == null) throw new IllegalStateException("Status input is not durable");
        if (envelope.assistantSequence() != null) throw new RecoveryPreparationDeferredException("Final status answer already exists; do not call the model again");
        return new RecoveryRequestContext(snapshot, new RequestAssemblyBoundary(request.id(), request.id(), envelope.userSequence()), true);
    }

    public ContinuationInvocation continuationInvocation(ContinuationCapability.Scope scope, RecoveryContextSnapshot snapshot) throws Exception {
        var target = capability.requireRunning(scope);
        requireSnapshot(scope, target, snapshot);
        var request = target.continuationRequestId() == null ? null : store.find(target.continuationRequestId()).orElse(null);
        if (request == null || request.requestSemantics() != RequestSemantics.CONTINUE
                || request.status() != ExecutionStatus.COMPLETED || request.outcome() != ExecutionOutcome.SUCCEEDED
                || !target.id().equals(request.controlTargetExecutionId()) || !Objects.equals(target.recoveryGeneration(), request.controlTargetGeneration())
                || !target.sessionId().equals(request.sessionId()) || !target.workspaceKey().equals(request.workspaceKey())
                || !scope.claim().ownerId().equals(request.controlOwnerId()) || !request.submittedInput().equals(target.currentPolicyInput()))
            throw new RecoveryPreparationDeferredException("Continuation ticket is unavailable or stale");
        if (target.requestSemantics() != null && target.requestSemantics() != RequestSemantics.NEW_REQUEST)
            throw new RecoveryPreparationDeferredException("Original request has a status/control contract; ordinary task continuation is unavailable");
        var projection = scope.context().session().projection();
        var input = projection.executionEnvelopes().get(request.id());
        var original = projection.executionEnvelopes().get(target.id());
        if (input == null || !input.ended() || input.userSequence() == null || input.assistantSequence() == null)
            throw new RecoveryPreparationDeferredException("Continuation input receipt is not durable");
        if (original != null && (original.ended() || original.assistantSequence() != null))
            throw new RecoveryPreparationDeferredException("Original final answer already exists; do not call the model again");
        if (target.id().startsWith("legacy-plan-") && (original == null || original.userSequence() == null))
            throw new RecoveryPreparationDeferredException("Legacy Plan needs its Plan-specific original-user adapter");
        var context = new RecoveryRequestContext(snapshot, new RequestAssemblyBoundary(target.id(), request.id(), input.userSequence()),
                false, original == null || original.userSequence() == null ? target.submittedInput() : null);
        return new ContinuationInvocation(target, request, request.submittedInput(), context);
    }

    private static LlmClient.Message requireUser(LlmClient.Message message) {
        if (message == null || !"user".equals(message.role())) throw new IllegalArgumentException("Current input must be a user message");
        return message;
    }
    private static ContinuationIntent committedIntent(RuntimeExecution e) {
        return switch (Objects.requireNonNull(e.requestSemantics(), "Unclassified request")) {
            case CONTINUE -> new ContinuationIntent(ContinuationIntent.Kind.CONTINUE, RequestSemantics.CONTINUE, e.controlTargetExecutionId(), e.controlTargetGeneration());
            case CLARIFY -> ContinuationIntent.clarify();
            case NEW_REQUEST, STATUS_QUESTION -> new ContinuationIntent(ContinuationIntent.Kind.NEW_TURN, e.requestSemantics(), null, null);
        };
    }
    private static void requireSnapshot(ContinuationCapability.Scope scope, RuntimeExecution request, RecoveryContextSnapshot snapshot) {
        if (!request.workspaceKey().value().equals(snapshot.workspaceKey()) || !request.sessionId().equals(snapshot.parentSessionId())
                || scope.context().session().manifest().lastEventSequence() != snapshot.parentLastSequence())
            throw new IllegalStateException("Recovery snapshot is outside current parent boundary");
    }
    public record Receipt(RuntimeExecution request, ContinuationReleaseResult releaseResult) { }
}
