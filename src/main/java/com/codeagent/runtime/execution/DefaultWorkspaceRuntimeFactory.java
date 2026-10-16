package com.codeagent.runtime.execution;

import com.codeagent.agent.*;
import com.codeagent.history.SessionStore;
import com.codeagent.hitl.HitlToolRegistry;
import com.codeagent.llm.LlmClient;
import com.codeagent.prompt.ModeRouterPromptBuilder;
import com.codeagent.prompt.PromptRepository;
import com.codeagent.runtime.interaction.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Headless Java production factory: real Agent/tool/policy/ledger wiring, no automatic MCP/index maintenance. */
public final class DefaultWorkspaceRuntimeFactory implements WorkspaceRuntimeFactory {
    private final RuntimeExecutionStore executions;
    private final SessionStore sessions;
    private final Function<WorkspaceIdentity, LlmClient> clients;
    private final PlanStoreFactory planStores;

    public DefaultWorkspaceRuntimeFactory(RuntimeExecutionStore executions, SessionStore sessions,
                                          Function<WorkspaceIdentity, LlmClient> clients) {
        this(executions, sessions, clients, com.codeagent.plan.PlanStateStore::openDefault);
    }
    public DefaultWorkspaceRuntimeFactory(RuntimeExecutionStore executions, SessionStore sessions,
            Function<WorkspaceIdentity, LlmClient> clients, PlanStoreFactory planStores) {
        this.executions = Objects.requireNonNull(executions); this.sessions = Objects.requireNonNull(sessions);
        this.clients = Objects.requireNonNull(clients);
        this.planStores = Objects.requireNonNull(planStores);
    }
    @Override public WorkspaceRuntime create(WorkspaceIdentity identity, ExecutionEventSink sink) throws Exception {
        LlmClient client = Objects.requireNonNull(clients.apply(identity));
        InteractionBroker broker = new InteractionBroker();
        java.util.function.Consumer<InteractionRequest> notify = request -> {
            if (!sink.supportsInteractions()) {
                broker.cancelExecution(request.executionId(), "No host interaction adapter");
                throw new IllegalStateException("Host does not support interactions");
            }
            sink.accept(new RuntimeExecutionEvent(identity.key(), request.sessionId(), request.executionId(), "interaction", request));
        };
        BrokerHitlHandler hitl = new BrokerHitlHandler(broker, notify);
        HitlToolRegistry tools = new HitlToolRegistry(hitl);
        tools.setProjectPath(identity.canonicalRoot().toString());
        ExecutionEventRenderer renderer = new ExecutionEventRenderer(identity, sink);
        SessionExecutionContextFactory contextFactory = new SessionExecutionContextFactory(sessions, identity.canonicalRoot(), buffer -> {
            Agent agent = new Agent(client, tools);
            agent.setRenderer(renderer);
            return agent;
        });
        SessionExecutionContextRegistry<SessionExecutionContext> contexts = new SessionExecutionContextRegistry<>(
                contextFactory::load, id -> {
                    try { return executions.hasNonTerminal(id); } catch (Exception e) { return true; }
                }, id -> broker.pending().map(request -> id.equals(request.sessionId())).orElse(false));
        ExecutionFinalizer finalizer = new ExecutionFinalizer(executions);
        var expander = new LocalPathMentionExpander(identity.canonicalRoot());
        var router = new ExecutionModeRouter(client, new ModeRouterPromptBuilder(
                new PromptRepository(null, identity.canonicalRoot().resolve(".codeagent/prompts"))));
        WorkspaceRuntime.SessionController controller = new WorkspaceRuntime.SessionController() {
            public String create() throws Exception {
                SessionStore.SessionHandle session = sessions.create(new SessionStore.SessionCreateRequest(identity.canonicalRoot(),
                        client.getProviderName(), client.getModelName(), null, "runtime", "runtime-host"));
                try {
                    Agent agent = new Agent(client, tools); agent.setRenderer(renderer);
                    var buffer = new com.codeagent.skill.SkillContextBuffer(); agent.setSkillContextBuffer(buffer); agent.attachSession(session);
                    contexts.adopt(session.sessionId(), new SessionExecutionContext(agent, session, buffer));
                    return session.sessionId();
                } catch (Exception | Error failure) { session.close(); throw failure; }
            }
            public void attach(String id) throws Exception { try (var lease = contexts.acquire(id)) { /* metadata checked on load */ } }
        };
        Set<String> resources;
        try { resources = sharedGitResources(identity.canonicalRoot()); }
        catch (Exception failure) { contexts.close(); broker.close(); renderer.close(); tools.getSnapshotService().close(); throw failure; }
        return new WorkspaceRuntime(identity, contexts, broker, new ReentrantLock(), resources, () -> {
                    if (tools.hasExternalMutableRuntimeBindings()) throw new IllegalStateException(
                            "Default headless factory does not support undeclared MCP/Browser bindings; register an explicit resource-aware factory");
                    return true;
                }, controller,
                (execution, token, context) -> {
                    Agent agent = context.agent();
                    finalizer.ensureStarted(execution, context.session());
                    sink.accept(event(execution, "started", null));
                    TopLevelExecutionResult proposed;
                    try {
                        var coordinator = new TopLevelExecutionCoordinator(executions, value -> expander.expand(value.submittedInput()),
                                router::route, id -> agent.getParentConversationContext().conversationNodes(),
                                (mode, input, action) -> tools.getSnapshotService().runTurnAndDrain(mode.name().toLowerCase(Locale.ROOT), input, action::run),
                                (value, input, cancellation) -> result(agent.runExecution(input, value.submittedInput())),
                                (value, input, cancellation) -> {
                                    var plan = new PlanExecuteAgent(client, tools, agent.getMemoryManager(),
                                            new BrokerPlanReviewHandler(broker, notify), renderer.stream(), PipelineOptions.FULL_PRESET);
                                    plan.setParentConversationContext(agent.getParentConversationContext());
                                    plan.setConversationLedger(agent.getConversationLedger());
                                    plan.setPlanStateStore(planStores.open()); plan.setRuntimeExecutionId(value.id());
                                    plan.setSkillContextBuffer(context.skillContextBuffer());
                                    return result(plan.runOrResumeExecution(input, value.submittedInput()));
                                });
                        proposed = coordinator.run(execution, token);
                    } catch (InterruptedException e) { throw e; }
                    catch (CancellationException e) { proposed = new TopLevelExecutionResult(null, null, ExecutionOutcome.CANCELED, null, null); }
                    catch (Exception e) { proposed = TopLevelExecutionResult.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
                    // Drain the writer before releasing the workspace. A drain failure fails closed in the scheduler.
                    tools.getSnapshotService().drain();
                    RuntimeExecution routed = executions.find(execution.id()).orElseThrow();
                    if (routed.selectedMode() == null) executions.commitRoutingDecision(execution.claim(),
                            execution.explicitMode() == null ? ExecutionMode.REACT : execution.explicitMode(),
                            execution.explicitMode() == null ? RoutingSource.AUTO_FALLBACK : RoutingSource.EXPLICIT);
                    RuntimeExecution terminal = finalizer.finish(execution.claim(), proposed, context.session());
                    broker.cancelExecution(execution.id(), "Execution ended");
                    sink.accept(event(terminal, "completed", terminal.outcome()));
                    return proposed;
                }, () -> { renderer.close(); tools.closeWorkspaceResources(); });
    }
    @FunctionalInterface public interface PlanStoreFactory {
        com.codeagent.plan.PlanStateStore open() throws java.sql.SQLException;
    }
    private static RuntimeExecutionEvent event(RuntimeExecution execution, String kind, Object payload) {
        return new RuntimeExecutionEvent(execution.workspaceKey(), execution.sessionId(), execution.id(), kind, payload);
    }
    private static TopLevelExecutionResult result(AgentExecutionResult result) {
        return new TopLevelExecutionResult(null, null, result.outcome(), result.result(), result.error());
    }
    public static Set<String> sharedGitResources(Path root) throws Exception {
        Process process = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "--git-common-dir")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) throw new java.io.IOException("Git identity probe timed out");
            if (process.exitValue() != 0) {
                // A normal non-repository is independent; malformed Git metadata must not be treated as independent.
                for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent()) {
                    if (java.nio.file.Files.exists(ancestor.resolve(".git"))) throw new java.io.IOException("Cannot verify Git common directory");
                }
                return Set.of();
            }
            String common = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (common.isEmpty()) throw new java.io.IOException("Git returned an empty common directory");
            Path commonPath = Path.of(common);
            if (!commonPath.isAbsolute()) commonPath = root.resolve(commonPath);
            return Set.of("git:" + commonPath.toRealPath());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
