package com.codeagent.runtime.execution;

import com.codeagent.agent.*;
import com.codeagent.history.*;
import com.codeagent.llm.LlmClient;
import com.codeagent.plan.*;
import com.codeagent.prompt.PromptRepository;
import com.codeagent.runtime.interaction.*;
import com.codeagent.skill.SkillContextBuffer;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;

class ContinuationExecutionDispatcherTest {
    @TempDir Path temp;
    @Test void longParentHistoryCannotStarveClassificationOfAStatusQuestion() throws Exception {
        try (var f = new Fixture()) {
            var old = f.claim("completed original task", ExecutionMode.REACT);
            f.store.commitRoutingDecision(old.claim(), ExecutionMode.REACT, RoutingSource.EXPLICIT);
            var finalizer = new ExecutionFinalizer(f.store);
            finalizer.ensureStarted(old, f.session);
            var parent = f.agent.getParentConversationContext();
            parent.appendExecutionMessage(old.id(), LlmClient.Message.user(old.submittedInput()), old.submittedInput(), "agent", "agent");
            int threshold = f.agent.getMemoryManager().getContextProfile().compressionTriggerTokens();
            String large = "historical assistant output ".repeat((threshold - 5000) / 7);
            parent.appendExecutionMessage(old.id(), LlmClient.Message.assistant(large), large, "agent", "agent");
            finalizer.finish(old.claim(), new TopLevelExecutionResult(ExecutionMode.REACT, RoutingSource.EXPLICIT,
                    ExecutionOutcome.SUCCEEDED, "done", null), f.session);
            var held = f.store.enqueue(temp, f.session.sessionId(), "remaining work", ExecutionMode.REACT);
            f.store.freezeForUser(f.owner);
            long base = com.codeagent.memory.TokenBudget.estimateMessagesTokens(parent.providerMessages());
            assertTrue(base > threshold - 4000 && base < threshold);
            f.client.response = "{\"intent\":\"NEW_TURN\",\"newTurnKind\":\"STATUS_QUESTION\",\"targetExecutionId\":null,\"targetGeneration\":null}";
            var status = f.claim("what progress was saved?", null);
            f.run(status);
            assertEquals(RequestSemantics.STATUS_QUESTION, f.store.find(status.id()).orElseThrow().requestSemantics());
            assertEquals(1, f.client.classifications);
            assertEquals(1, f.client.answers);
            assertTrue(f.client.lastTools.isEmpty());
            assertTrue(f.store.find(held.id()).orElseThrow().awaitingInput());
        }
    }
    @Test void ordinaryExplicitAndAutomaticRequestsDoNotDependOnRecoveryClassifier() throws Exception {
        try (var f = new Fixture()) {
            f.client.response = "classifier unavailable";
            var skills = new SkillContextBuffer();
            skills.push("ordinary", "ORDINARY_SKILL_MUST_REACH_MODEL");
            f.agent.setSkillContextBuffer(skills);
            var explicit = f.claim("inspect the current source", ExecutionMode.REACT);
            var queued = f.store.enqueue(temp, f.session.sessionId(), "another ready task", null);
            f.run(explicit);
            assertEquals(0, f.client.classifications);
            assertEquals(1, f.client.answers);
            assertEquals(RequestSemantics.NEW_REQUEST, f.store.find(explicit.id()).orElseThrow().requestSemantics());
            assertTrue(f.client.answer.stream().anyMatch(m -> m.content() != null && m.content().contains("ORDINARY_SKILL_MUST_REACH_MODEL")));
            var automatic = f.store.claimCandidate(f.owner, queued.id()).orElseThrow();
            f.run(automatic);
            assertEquals(0, f.client.classifications);
            assertEquals(1, f.client.modeRoutes);
            assertEquals(2, f.client.answers);
            assertEquals(ExecutionOutcome.SUCCEEDED, f.store.find(automatic.id()).orElseThrow().outcome());
        }
    }

    @Test void ordinaryExplicitPlanReachesPlanAdapterWithoutRecoveryClassifier() throws Exception {
        try (var f = new Fixture()) {
            f.plan = new PlanExecuteAgent(f.client, f.tools, null, (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()) {
                @Override public AgentExecutionResult runOrResumeExecution(String input, String submitted) {
                    assertEquals("new plan task", input);
                    return AgentExecutionResult.succeeded("new plan finished");
                }
            };
            var e = f.claim("new plan task", ExecutionMode.PLAN);
            f.run(e);
            assertEquals(0, f.client.classifications);
            assertEquals(ExecutionMode.PLAN, f.store.find(e.id()).orElseThrow().selectedMode());
            assertEquals("new plan finished", f.store.find(e.id()).orElseThrow().result());
        }
    }

    @Test void waitingTaskInAnotherSessionDoesNotChangeOrdinaryDispatch() throws Exception {
        try (var f = new Fixture(); var other = f.sessions.create(new SessionStore.SessionCreateRequest(
                temp, "test", "test", null, "react", "agent"))) {
            var old = f.store.enqueue(temp, other.sessionId(), "foreign-session goal", null);
            f.store.freezeForUser(f.owner);
            var current = f.claim("independent current-session task", ExecutionMode.REACT);
            f.run(current);
            assertEquals(0, f.client.classifications);
            assertEquals(1, f.client.answers);
            assertTrue(f.store.find(old.id()).orElseThrow().awaitingInput());
        }
    }

    @Test void noHeldTasksCannotEraseCommittedStatusOrUnclassifiedHistoricalInput() throws Exception {
        try (var f = new Fixture()) {
            var status = f.claim("what was recorded?", null);
            f.store.commitRequestSemantics(status.claim(), RequestSemantics.STATUS_QUESTION);
            f.run(f.store.find(status.id()).orElseThrow());
            assertTrue(f.client.lastTools.isEmpty());
            assertEquals(RequestSemantics.STATUS_QUESTION, f.store.find(status.id()).orElseThrow().requestSemantics());
            var old = f.claim("old persisted user", ExecutionMode.REACT);
            new ExecutionFinalizer(f.store).ensureStarted(old, f.session);
            f.agent.getParentConversationContext().appendExecutionMessage(old.id(), LlmClient.Message.user(old.submittedInput()), old.submittedInput(), "agent", "agent");
            assertThrows(RecoveryPreparationDeferredException.class, () -> f.run(old));
            assertEquals(0, f.client.classifications);
        }
    }
    @Test void statusAndNewRequestUseRealFreshContextAndLeaveOriginalHeld() throws Exception {
        try (var f = new Fixture()) {
            var old = f.store.enqueue(temp, f.session.sessionId(), "original work", null);
            f.store.freezeForUser(f.owner);
            f.client.response = "{\"intent\":\"NEW_TURN\",\"newTurnKind\":\"STATUS_QUESTION\",\"targetExecutionId\":null,\"targetGeneration\":null}";
            var status = f.claim("progress?", null);
            f.run(status);
            assertTrue(f.client.lastTools.isEmpty());
            assertTrue(f.client.answer.stream().anyMatch(m -> m.content()!=null && m.content().contains(old.id())));
            assertTrue(f.store.find(old.id()).orElseThrow().awaitingInput());
            assertEquals(RequestSemantics.STATUS_QUESTION, f.store.find(status.id()).orElseThrow().requestSemantics());
            f.client.response = "{\"intent\":\"NEW_TURN\",\"newTurnKind\":\"NEW_REQUEST\",\"targetExecutionId\":null,\"targetGeneration\":null}";
            var fresh = f.claim("new independent question", ExecutionMode.REACT);
            f.run(fresh);
            assertTrue(f.store.find(old.id()).orElseThrow().awaitingInput());
            assertEquals(1, f.session.readAll().stream().filter(e -> SessionEvent.Types.USER_MESSAGE.equals(e.type()) && fresh.id().equals(e.payload().path("executionId").asText())).count());
        }
    }
    @Test void explicitContinueEndsControlBeforeOriginalAgentRunsWithCurrentInput() throws Exception {
        try (var f = new Fixture()) {
            var old = f.store.enqueue(temp, f.session.sessionId(), "original work", ExecutionMode.REACT);
            f.store.freezeForUser(f.owner);
            f.client.response = "{\"intent\":\"CONTINUE\",\"newTurnKind\":null,\"targetExecutionId\":\""+old.id()+"\",\"targetGeneration\":1}";
            var control = f.claim("continue only inspect", null);
            f.run(control);
            assertTrue(f.session.projection().executionEnvelopes().get(control.id()).ended());
            var resumed = f.store.claimCandidate(f.owner, old.id()).orElseThrow();
            assertEquals(0, f.client.answers);
            f.run(resumed);
            assertEquals(1, f.client.answers);
            assertEquals(1, f.client.answer.stream().filter(m -> "user".equals(m.role()) && "continue only inspect".equals(m.content())).count());
            assertNotNull(f.session.projection().executionEnvelopes().get(old.id()).userSequence());
        }
    }
    @Test void explicitPlanResumeKeepsOriginalPlanAndSkipsCompletedNode() throws Exception {
        try(var f=new Fixture()) {
            var old=f.claim("original plan goal",ExecutionMode.PLAN);
            f.store.commitRoutingDecision(old.claim(),ExecutionMode.PLAN,RoutingSource.EXPLICIT);
            new ExecutionFinalizer(f.store).ensureStarted(old,f.session);
            var plan=new ExecutionPlan("same-plan","original plan goal");
            var done=new Task("done","completed task must not run",Task.TaskType.ANALYSIS);done.markCompleted("recorded");
            var pending=new Task("pending","remaining task",Task.TaskType.ANALYSIS);pending.addDependency("done");
            plan.addTask(done);plan.addTask(pending);assertTrue(plan.computeExecutionOrder());plan.markStarted();
            f.plans.savePlan(temp,f.session.sessionId(),old.id(),old.submittedInput(),plan);
            f.store.freezeForUser(f.owner);
            f.plan=new PlanExecuteAgent(f.client,f.tools,null,
                    (g,p)->PlanExecuteAgent.PlanReviewDecision.execute(),new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),PipelineOptions.PLAN_PRESET);
            f.plan.setPlanStateStore(f.plans);f.plan.setParentConversationContext(f.agent.getParentConversationContext());
            var control=f.claim("/plan resume",null);f.run(control);
            assertTrue(f.session.projection().executionEnvelopes().get(control.id()).ended());
            f.run(f.store.claimCandidate(f.owner,old.id()).orElseThrow());
            assertEquals(1,f.client.answers);
            assertTrue(f.client.answer.stream().anyMatch(m->m.content()!=null&&m.content().contains("/plan resume")));
            assertEquals(ExecutionPlan.PlanStatus.COMPLETED,f.plans.findById("same-plan").orElseThrow().plan().getStatus());
            assertEquals(1,f.plans.findByExecution(temp,f.session.sessionId(),old.id()).size());
            assertNotNull(f.session.projection().executionEnvelopes().get(old.id()).userSequence());
        }
    }
    final class Fixture implements AutoCloseable {
        final RuntimeExecutionStore store = new RuntimeExecutionStore(temp.resolve("runtime.db"));
        final SessionStore sessions = SessionStore.open(temp.resolve("history"));
        final SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(temp,"test","test",null,"react","agent"));
        final PlanStateStore plans = new PlanStateStore(temp.resolve("plans.db"));
        final TaskRecoveryStore attempts = new TaskRecoveryStore(plans.dbPath());
        final WorkspaceIdentity identity = new WorkspaceIdentityResolver().resolve(temp);
        final WorkspaceOwnerLease owner = WorkspaceOwnerLease.acquire(temp.resolve("locks"),identity,"owner");
        final Client client = new Client(); final ToolRegistry tools = new ToolRegistry();
        final Agent agent = new Agent(client,tools);
        final SessionExecutionContext context;
        final WorkspaceRuntime runtime;
        final SessionExecutionContextRegistry.Lease<SessionExecutionContext> lease;
        final ContinuationExecutionDispatcher dispatcher;
        PlanExecuteAgent plan;
        Fixture() throws Exception {
            System.setProperty("codeagent.snapshot.enabled","false");
            tools.setProjectPath(temp.toString()); agent.attachSession(session);
            context = new SessionExecutionContext(agent,session,new SkillContextBuffer());
            var registry = new SessionExecutionContextRegistry<SessionExecutionContext>(id -> context,id -> true,id -> false);
            registry.adopt(session.sessionId(),context);
            runtime = new WorkspaceRuntime(identity,registry,new InteractionBroker(),new ReentrantLock(),Set.of(),()->true,
                    new WorkspaceRuntime.SessionController(){ public String create(){return session.sessionId();} public void attach(String id){} },(e,t,c)->null,()->{});
            runtime.bindContinuationCapability(new ContinuationCapability(store,owner,runtime,()->{}));
            runtime.mutationGate().lock(); lease = registry.acquire(session.sessionId());
            dispatcher = new ContinuationExecutionDispatcher(store,plans,sessions,attempts,client,new PromptRepository(null,null),
                    e -> e.submittedInput(), () -> { if(plan==null)throw new AssertionError("Plan must not run"); return plan; });
        }
        RuntimeExecution claim(String input,ExecutionMode mode) throws Exception {
            return store.claimCandidate(owner,store.enqueue(temp,session.sessionId(),input,mode).id()).orElseThrow();
        }
        void run(RuntimeExecution execution) throws Exception {
            try(var ignored = ExecutionInteractionContext.install(execution.id(),execution.sessionId())) {
                var result = dispatcher.run(runtime,execution,null,context);
                if(!store.find(execution.id()).orElseThrow().terminal())new ExecutionFinalizer(store).finish(execution.claim(),result,session);
            }
        }
        public void close() throws Exception { lease.close();runtime.mutationGate().unlock();runtime.close();owner.close();sessions.close();store.close();tools.closeWorkspaceResources();System.clearProperty("codeagent.snapshot.enabled"); }
    }
    static class Client implements LlmClient {
        String response; int answers, classifications, modeRoutes; List<Message> answer=List.of(); List<Tool> lastTools=List.of();
        public String getProviderName(){return "test";} public String getModelName(){return "test";}
        public ChatResponse chat(List<Message> m,List<Tool> t){
            if(m.get(0).content().contains("continuation_intent") || m.get(0).content().contains("NEW_TURN")) {
                classifications++;
                return new ChatResponse("assistant",response,null,null,1,1,0);
            }
            if (m.get(0).content().contains("执行模式路由器")) {
                modeRoutes++;
                return new ChatResponse("assistant", "{\"mode\":\"react\"}", null, null, 1, 1, 0);
            }
            answers++;answer=List.copyOf(m);lastTools=t==null?List.of():List.copyOf(t);
            return new ChatResponse("assistant","waiting work described",null,null,1,1,0);
        }
        public ChatResponse chat(List<Message> m,List<Tool> t,StreamListener l){return chat(m,t);}
    }
}
