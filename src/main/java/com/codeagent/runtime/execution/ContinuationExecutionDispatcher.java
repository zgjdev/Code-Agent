package com.codeagent.runtime.execution;

import com.codeagent.agent.*;
import com.codeagent.history.*;
import com.codeagent.image.ImageReferenceParser;
import com.codeagent.llm.LlmClient;
import com.codeagent.memory.TokenBudget;
import com.codeagent.model.recovery.*;
import com.codeagent.plan.*;
import com.codeagent.prompt.*;
import com.codeagent.runtime.CancellationToken;
import java.util.Objects;
import java.util.function.Supplier;

/** The CLI worker adapter. All reads and admission run inside the Host's physical scope. */
public final class ContinuationExecutionDispatcher {
    private final RuntimeExecutionStore store;
    private final PlanStateStore plans;
    private final SessionStore sessions;
    private final TaskRecoveryStore attempts;
    private final LlmClient client;
    private final PromptRepository prompts;
    private final TopLevelExecutionCoordinator.ExecutionInputResolver resolver;
    private final Supplier<PlanExecuteAgent> planFactory;

    public ContinuationExecutionDispatcher(RuntimeExecutionStore store, PlanStateStore plans,
            SessionStore sessions, TaskRecoveryStore attempts, LlmClient client, PromptRepository prompts,
            TopLevelExecutionCoordinator.ExecutionInputResolver resolver, Supplier<PlanExecuteAgent> planFactory) {
        this.store=store; this.plans=plans; this.sessions=sessions; this.attempts=attempts;
        this.client=client; this.prompts=prompts; this.resolver=resolver; this.planFactory=planFactory;
    }

    public TopLevelExecutionResult run(WorkspaceRuntime runtime, RuntimeExecution execution,
            CancellationToken token, SessionExecutionContext context) throws Exception {
        var capability=runtime.continuationCapability();
        var scope=capability.requireRunningScope(execution.claim(),context);
        var admission=new ContinuationAdmissionService(store,capability);
        var provider=new RecoveryContextProvider(store,plans,sessions,attempts,
                (identity,id,claim)->capability.verifyRecoveryBoundary(scope,identity,id,claim));
        var boundary=new RecoveryContextProvider.ReadBoundary(runtime.identity(),context.sessionId(),execution.claim());
        var finalizer=new ExecutionFinalizer(store);
        finalizer.ensureStarted(execution,context.session());
        var snapshot=provider.readRecoveryContext(boundary);
        var agent=context.agent();
        var budget=budget(agent);
        ContinuationInvocation invocation=null;
        if(execution.continuationRequestId()!=null) {
            invocation=admission.continuationInvocation(scope,snapshot);
        } else {
            ContinuationIntent intent;
            if("/plan resume".equals(execution.submittedInput())) {
                var active=plans.findActiveRecord(runtime.identity().canonicalRoot(),context.sessionId());
                var target=active.isEmpty()||active.get().executionId()==null?null:store.find(active.get().executionId()).orElse(null);
                intent=target!=null&&target.awaitingInput()
                        ?new ContinuationIntent(ContinuationIntent.Kind.CONTINUE,RequestSemantics.CONTINUE,target.id(),target.recoveryGeneration())
                        :ContinuationIntent.clarify();
            } else intent=admission.classify(scope,new ContinuationIntentRouter(client,prompts),snapshot,budget);
            var current=store.find(execution.id()).orElseThrow();
            String resolved=current.resolvedTaskInput();
            if(resolved==null)resolved=store.commitResolvedInput(execution.claim(),resolver.resolve(current)).resolvedTaskInput();
            var message=ImageReferenceParser.userMessage(resolved,runtime.identity().canonicalRoot());
            if(intent.kind()!=ContinuationIntent.Kind.NEW_TURN) {
                var receipt=admission.accept(scope,intent,message);
                return new TopLevelExecutionResult(receipt.request().selectedMode(),receipt.request().routingSource(),
                        receipt.request().outcome(),intent.kind()==ContinuationIntent.Kind.CONTINUE
                        ?"已接受继续请求；原任务将在调度后继续执行。":"请明确要继续的任务；原任务仍在等待，不会自动执行。",null);
            }
            if(intent.semantics()==RequestSemantics.STATUS_QUESTION) {
                admission.persistStatusInput(scope,message);
                var recovery=admission.statusContext(scope,provider.readRecoveryContext(boundary));
                return result(agent.runExecution(resolved,current.submittedInput(),recovery));
            }
        }
        final var continued=invocation;
        final String policy=continued==null?execution.submittedInput():continued.currentInput();
        var formatted=new RecoveryContextFormatter().formatForParent(snapshot,budget,continued==null?execution.id():continued.inputExecution().id());
        var coordinator=new TopLevelExecutionCoordinator(store,
                e->continued==null?resolver.resolve(e):e.resolvedTaskInput()==null?e.submittedInput():e.resolvedTaskInput(),
                (input,history)->new ExecutionModeRouter(client,new ModeRouterPromptBuilder(prompts)).route(input,history,formatted),
                id->agent.getParentConversationContext().conversationNodes(),
                (mode,input,action)->agent.getToolRegistry().getSnapshotService().runTurnAndDrain(mode.name().toLowerCase(java.util.Locale.ROOT),policy,action::run),
                (e,resolved,t)->{
                    RecoveryRequestContext recovery;
                    if(continued!=null) recovery=admission.continuationInvocation(scope,provider.readRecoveryContext(boundary)).recoveryContext();
                    else {
                        agent.getParentConversationContext().appendExecutionMessage(e.id(),
                                ImageReferenceParser.userMessage(resolved,runtime.identity().canonicalRoot()),e.submittedInput(),"agent","agent");
                        recovery=currentContext(provider.readRecoveryContext(boundary),context,e);
                    }
                    return result(agent.runExecution(resolved,policy,recovery));
                },
                (e,resolved,t)->{
                    var plan=Objects.requireNonNull(planFactory.get());
                    plan.setRuntimeExecutionId(e.id());
                    plan.setTaskRecoveryIdentity(new TaskExecutionIdentity(e.id(),e.sessionId(),e.claim().ownerId(),e.attempt()));
                    plan.setTaskRecoveryStore(attempts);
                    plan.setTaskTranscriptReader(new TaskTranscriptReader(sessions,attempts));
                    plan.setRecoveryContextSupplier(()->continued==null?currentContext(provider.readRecoveryContext(boundary),context,e)
                            :admission.continuationInvocation(scope,provider.readRecoveryContext(boundary)).recoveryContext());
                    return result(continued==null?plan.runOrResumeExecution(resolved,policy):plan.runContinuation(continued));
                });
        return coordinator.run(store.find(execution.id()).orElseThrow(),token);
    }

    private RecoveryContextBudget budget(Agent agent) {
        int reserve=2000;
        int limit=Math.min(client.maxContextWindow(),agent.getMemoryManager().getContextProfile().compressionTriggerTokens());
        long base=TokenBudget.estimateMessagesTokens(agent.getParentConversationContext().providerMessages());
        return new RecoveryContextBudget(Math.max(0,limit-base-reserve),4096,reserve);
    }
    private static RecoveryRequestContext currentContext(RecoveryContextSnapshot snapshot,SessionExecutionContext context,RuntimeExecution execution) {
        var envelope=context.session().projection().executionEnvelopes().get(execution.id());
        if(envelope==null||envelope.userSequence()==null)throw new IllegalStateException("Current Plan input is not durable");
        return new RecoveryRequestContext(snapshot,new RequestAssemblyBoundary(execution.id(),execution.id(),envelope.userSequence()),false);
    }
    private static TopLevelExecutionResult result(AgentExecutionResult r) {
        return new TopLevelExecutionResult(null,null,r.outcome(),r.displayResult(),r.error());
    }
}
