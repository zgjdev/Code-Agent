package com.codeagent.agent;

import com.codeagent.context.RequestSnapshotFactory;
import com.codeagent.history.*;
import com.codeagent.llm.*;
import com.codeagent.model.recovery.*;
import com.codeagent.runtime.interaction.ExecutionInteractionContext;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRecoveryRequestTest {
    @TempDir Path dir;

    @Test void persistedCurrentInputStillReceivesMemoryAndSkillAcrossToolRequests() throws Exception {
        org.slf4j.LoggerFactory.getLogger(Agent.class);
        String priorHome = System.getProperty("codeagent.home");
        System.setProperty("codeagent.home", dir.resolve("memory-home").toString());
        try (var store = SessionStore.open(dir.resolve("history")); var s = create(store)) {
            var call = new LlmClient.ToolCall("read", new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}"));
            var client = new Client(List.of(new LlmClient.ChatResponse("assistant", "checking", List.of(call), 10, 2), response("done")));
            var agent = agent(client);
            agent.attachSession(s);
            try {
                agent.getMemoryManager().setProjectPath(dir.toString());
                String fact = "RESUME_MEMORY_CONVENTION use constructor injection";
                agent.getMemoryManager().storeFactWithResult(fact, "project", "remember " + fact);
                assertTrue(agent.getMemoryManager().buildContextForQuery(fact, 4000).contains(fact));
                var skills = new com.codeagent.skill.SkillContextBuffer();
                skills.push("resume-regression", "RESUME_SKILL_BODY inspect before editing");
                agent.setSkillContextBuffer(skills);
                RecoveryRequestAugmenterTest.start(s, "current", 1);
                var input = LlmClient.Message.user(fact);
                long seq = agent.getParentConversationContext().appendExecutionMessage("current", input, fact, "agent", "agent").sequence();
                var durable = s.readAll().stream().filter(e -> e.sequence() == seq).findFirst().orElseThrow();
                assertEquals(com.codeagent.runtime.execution.ExecutionOutcome.SUCCEEDED,
                        agent.runExecution(fact, fact, context(s, "current", "current", seq, false, "a")).outcome());
                assertEquals(2, client.messages.size());
                for (var sent : client.messages) {
                    var enriched = sent.stream().filter(m -> "user".equals(m.role()) && m.content() != null
                            && m.content().contains("RESUME_SKILL_BODY")).toList();
                    assertEquals(1, enriched.size(), "Skill must reach each real request exactly once");
                    assertTrue(enriched.get(0).content().contains("## 相关长期记忆"));
                }
                assertTrue(skills.isEmpty());
                assertEquals(durable, s.readAll().stream().filter(e -> e.sequence() == seq).findFirst().orElseThrow());
                assertEquals(1, s.readAll().stream().filter(e -> SessionEvent.Types.USER_MESSAGE.equals(e.type())
                        && e.payload().has("conversation")).count());
                assertEquals(agent.getConversationHistory(), s.projection().messages());
                assertSnapshotMatches(s, client);
            } finally { agent.getMemoryManager().close(); }
        } finally {
            if (priorHome == null) System.clearProperty("codeagent.home"); else System.setProperty("codeagent.home", priorHome);
        }
    }

    @Test void compactedCurrentInputIsDeductedBeforeRecoverySummaryBudget() throws Exception {
        try(var store=SessionStore.open(dir.resolve("history"));var s=create(store)) {
            var client=new Client(List.of(response("must not send"))) {
                @Override public int maxContextWindow(){return 16000;}
                @Override public boolean supportsTools(){return false;}
            };
            var agent=agent(client);agent.attachSession(s);
            RecoveryRequestAugmenterTest.start(s,"original",1);RecoveryRequestAugmenterTest.message(s,"original","user","old goal",false);
            RecoveryRequestAugmenterTest.start(s,"control",2);String current="继续检查文件。".repeat(5000);
            long seq=RecoveryRequestAugmenterTest.message(s,"control","user",current,true);
            var system=s.projection().messages().get(0);
            s.append(new SessionEventDraft(SessionEvent.Types.SURFACE_CLEAR,"react","agent","agent",false,
                    SessionEvent.SurfaceOperation.clear(),new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()));
            var retained=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            retained.set("message",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(system));
            s.append(new SessionEventDraft(SessionEvent.Types.SYSTEM_MESSAGE,"react","agent","agent",false,SessionEvent.SurfaceOperation.append(),retained));
            var failure=assertThrows(RecoveryContextBudgetExceededException.class,
                    ()->agent.runExecution("old goal",current,context(s,"original","control",seq,false,"b")));
            assertEquals(0,failure.limitTokens());assertTrue(client.messages.isEmpty());
            assertFalse(s.readAll().stream().anyMatch(e->SessionEvent.Types.REQUEST_STARTED.equals(e.type())));
            assertFalse(s.projection().messages().stream().anyMatch(m->current.equals(m.content())));
        }
    }

    @Test void neverStartedOriginalUserBecomesBackgroundBeforeCurrentControlInput() throws Exception {
        try(var store=SessionStore.open(dir.resolve("history"));var s=create(store)) {
            var client=new Client(List.of(response("done")));var agent=agent(client);agent.attachSession(s);
            RecoveryRequestAugmenterTest.start(s,"original",1);
            RecoveryRequestAugmenterTest.start(s,"control",2);long seq=RecoveryRequestAugmenterTest.message(s,"control","user","continue with current constraint",true);RecoveryRequestAugmenterTest.message(s,"control","assistant","accepted",true);
            var missingOriginal=context(s,"original","control",seq,false,"b");
            long before=s.projection().lastAppliedSequence();
            assertThrows(IllegalArgumentException.class,()->agent.runExecution("expanded original delivery","continue with current constraint",missingOriginal));
            assertEquals(before,s.projection().lastAppliedSequence());assertTrue(client.messages.isEmpty());
            var exactOriginal=new RecoveryRequestContext(missingOriginal.snapshot(),missingOriginal.boundary(),false,"durable queued original input");
            agent.runExecution("queued original goal","continue with current constraint",exactOriginal);
            var messages=client.messages.get(0);
            assertEquals("continue with current constraint",messages.get(messages.size()-1).content());
            assertEquals(1,messages.stream().filter(m->"queued original goal".equals(m.content())).count());
            int background = java.util.stream.IntStream.range(0,messages.size()).filter(i -> "queued original goal".equals(messages.get(i).content())).findFirst().orElseThrow();
            assertTrue(background < messages.size()-2);
            assertTrue(messages.get(messages.size()-2).content().contains("runtime_recovery_context"));
            var original=s.projection().executionEnvelopes().get("original");
            assertTrue(original.userSequence()>seq);assertNotNull(original.assistantSequence());
            assertEquals("durable queued original input",s.readAll().stream().filter(e->e.sequence()==original.userSequence()).findFirst().orElseThrow().payload().path("conversation").path("content").asText());
            assertFalse(exactOriginal.toString().contains("durable queued"));
            assertEquals(2,s.readAll().stream().filter(e->SessionEvent.Types.USER_MESSAGE.equals(e.type())).count());
        }
    }

    @Test void historicalOriginalMemoryIntentDoesNotAuthorizeCurrentMemoryWrite() throws Exception {
        String previousHome=System.getProperty("codeagent.home");
        System.setProperty("codeagent.home",dir.resolve("memory").toString());
        try(var store=SessionStore.open(dir.resolve("history"));var s=create(store)) {
            var client=new Client(List.of(response("done")));var agent=agent(client);agent.attachSession(s);
            try {
                String old="访问 https://example.test，复用已登录 Chrome，记一下";
                RecoveryRequestAugmenterTest.start(s,"original",1);RecoveryRequestAugmenterTest.message(s,"original","user",old,false);
                RecoveryRequestAugmenterTest.start(s,"control",2);long seq=RecoveryRequestAugmenterTest.message(s,"control","user","继续检查文件",true);
                agent.runExecution(old,"继续检查文件",context(s,"original","control",seq,false,"b"));
                assertEquals(0,agent.getMemoryManager().getLongTermMemory().size());
                assertSnapshotMatches(s,client);
            } finally {agent.getMemoryManager().close();}
        } finally {if(previousHome==null)System.clearProperty("codeagent.home");else System.setProperty("codeagent.home",previousHome);}
    }

    @Test void recoveryFingerprintInvalidatesOtherwiseUsableProviderUsageAnchor() throws Exception {
        try(var store=SessionStore.open(dir.resolve("history"));var s=create(store)) {
            var predictions=new ArrayList<com.codeagent.context.ContextTokenTracker.Mode>();
            var ref=new Agent[1];
            var client=new Client(List.of(response("prior answer"),response("state answer"))) {
                @Override public boolean supportsTools(){return false;}
                @Override public com.codeagent.context.MeasuredUsage normalizeUsage(ChatResponse r) {
                    return new com.codeagent.context.MeasuredUsage(100000,2,0,com.codeagent.context.MeasuredUsage.InputScope.TOTAL_PROMPT,true,true,true,java.time.Instant.now());
                }
                @Override public ChatResponse chat(List<Message> m,List<Tool> t,StreamListener l)throws IOException {
                    predictions.add(ref[0].currentContextPrediction().mode());return super.chat(m,t,l);
                }
            };
            var agent=agent(client);ref[0]=agent;agent.attachSession(s);agent.run("prior question");
            assertEquals(com.codeagent.context.ContextTokenTracker.Mode.USAGE_ANCHORED_DELTA,agent.currentContextPrediction().mode());
            RecoveryRequestAugmenterTest.start(s,"status",1);long seq=RecoveryRequestAugmenterTest.message(s,"status","user","state?",false);
            agent.runExecution("state?","state?",context(s,"status","status",seq,true,"c"));
            assertEquals(com.codeagent.context.ContextTokenTracker.Mode.FULL_ESTIMATE,predictions.get(1));
        }
    }

    @Test void automaticCompactionHappensBeforeAnOversizedBaseCanStarveRecoveryCore() throws Exception {
        try(var store=SessionStore.open(dir.resolve("history"));var s=create(store)) {
            var client=new Client(List.of(response("done"))) {
                @Override public boolean supportsTools(){return false;}
                @Override public int maxContextWindow(){return 16000;}
            };
            var agent=agent(client);agent.attachSession(s);
            RecoveryRequestAugmenterTest.start(s,"original",1);RecoveryRequestAugmenterTest.message(s,"original","user","goal "+"history ".repeat(8000),false);
            for(int i=0;i<4;i++)addAnswered(s,"p"+i,2+i,"progress "+i,"answer "+i);
            RecoveryRequestAugmenterTest.start(s,"control",6);long seq=RecoveryRequestAugmenterTest.message(s,"control","user","continue",true);
            assertEquals(com.codeagent.runtime.execution.ExecutionOutcome.SUCCEEDED,agent.runExecution("goal","continue",context(s,"original","control",seq,false,"a")).outcome());
            assertTrue(s.projection().compactionGeneration()>0);assertEquals(1,client.messages.size());assertSnapshotMatches(s,client);
            assertEquals(agent.getConversationHistory(),s.projection().messages());
            assertTrue(client.summaries.stream().flatMap(List::stream).noneMatch(m->m.content()!=null&&m.content().contains("a".repeat(64))));
        }
    }

    @Test void realDurableCompactionRematerializesOnlyTheExactControlInput() throws Exception {
        try(var store=SessionStore.open(dir.resolve("history"));var s=create(store)) {
            var client=new Client(List.of(response("summary"),response("answer")));
            var agent=agent(client);agent.attachSession(s);
            RecoveryRequestAugmenterTest.start(s,"original",1);RecoveryRequestAugmenterTest.message(s,"original","user","goal",false);
            RecoveryRequestAugmenterTest.start(s,"control",2);long seq=RecoveryRequestAugmenterTest.message(s,"control","user","continue",true);RecoveryRequestAugmenterTest.message(s,"control","assistant","accepted",true);
            addAnswered(s,"progress1",3,"progress one?","progress one answer");
            addAnswered(s,"progress2",4,"progress two?","progress two answer");
            agent.getParentConversationContext().synchronizeProviderFromProjection();
            assertTrue(agent.getParentConversationContext().compactNow("react","agent","test").compacted());
            assertFalse(s.projection().activeSurface().stream().anyMatch(n->n.sequence()==seq));
            assertEquals(com.codeagent.runtime.execution.ExecutionOutcome.SUCCEEDED,agent.runExecution("goal","continue",context(s,"original","control",seq,false,"b")).outcome());
            var sent=client.messages.get(0);
            assertEquals(1,sent.stream().filter(m->"continue".equals(m.content())).count());
            assertTrue(sent.stream().anyMatch(m->"progress two answer".equals(m.content())));
            assertEquals(agent.getConversationHistory(),s.projection().messages());
            assertFalse(Files.readString(s.sessionDirectory().resolve("events.jsonl")).contains("b".repeat(64)));
        }
    }

    @Test void overflowRetryCompactsOnlyParentAndRebuildsIdenticalSendSnapshot() throws Exception {
        try(var store=SessionStore.open(dir.resolve("history"));var s=create(store)) {
            var client=new Client(List.of(response("done")));client.overflowOnce=true;
            var agent=agent(client);agent.attachSession(s);
            RecoveryRequestAugmenterTest.start(s,"original",1);RecoveryRequestAugmenterTest.message(s,"original","user","goal",false);
            addAnswered(s,"p1",2,"progress?","progress answer");
            RecoveryRequestAugmenterTest.start(s,"control",3);long seq=RecoveryRequestAugmenterTest.message(s,"control","user","continue",true);RecoveryRequestAugmenterTest.message(s,"control","assistant","accepted",true);
            assertEquals(com.codeagent.runtime.execution.ExecutionOutcome.SUCCEEDED,agent.runExecution("goal","continue",context(s,"original","control",seq,false,"c")).outcome());
            assertEquals(1,client.summaries.size());assertFalse(client.summaries.get(0).toString().contains("c".repeat(64)));
            assertEquals(2,client.messages.size());assertSnapshotMatches(s,client);
            assertTrue(s.projection().compactionGeneration()>0);
            assertEquals(agent.getConversationHistory(),s.projection().messages());
        }
    }

    @Test void compactedHistoryPrecedesCurrentInputAcrossActualNewToolRequests() throws Exception {
        try(var store=SessionStore.open(dir.resolve("history"));var s=create(store)) {
            var call=new LlmClient.ToolCall("new-call",new LlmClient.ToolCall.Function("list_dir","{\"path\":\".\"}"));
            var client=new Client(List.of(new LlmClient.ChatResponse("assistant","reading",List.of(call),10,2),response("done")));
            var agent=agent(client);agent.attachSession(s);
            RecoveryRequestAugmenterTest.start(s,"original",1);RecoveryRequestAugmenterTest.message(s,"original","user","old goal",false);
            RecoveryRequestAugmenterTest.start(s,"control",2);long seq=RecoveryRequestAugmenterTest.message(s,"control","user","list current files",true);
            RecoveryRequestAugmenterTest.message(s,"control","assistant","accepted",true);
            RecoveryRequestAugmenterTest.startRequest(s,"original","prior-resumed");
            for(int i=1;i<=3;i++)RecoveryRequestAugmenterTest.appendInternal(s,LlmClient.Message.user("lsp diagnostic "+i));
            RecoveryRequestAugmenterTest.compactToSummary(s);
            assertEquals(com.codeagent.runtime.execution.ExecutionOutcome.SUCCEEDED,agent.runExecution("old goal","list current files",context(s,"original","control",seq,false,"a")).outcome());
            assertEquals(2,client.messages.size());assertSnapshotMatches(s,client);
            for(var sent:client.messages) {
                assertEquals("HISTORICAL SUMMARY",sent.get(1).content());assertEquals("SUMMARY ACK",sent.get(2).content());
                assertTrue(sent.get(3).content().contains("runtime_recovery_context"));assertEquals("list current files",sent.get(4).content());
                assertEquals(List.of("lsp diagnostic 1","lsp diagnostic 2","lsp diagnostic 3"),sent.subList(5,8).stream().map(LlmClient.Message::content).toList());
                assertEquals(1,sent.stream().filter(m->"list current files".equals(m.content())).count());
            }
            var second=client.messages.get(1);
            assertEquals(List.of(call),second.get(8).toolCalls());assertEquals("new-call",second.get(9).toolCallId());
            assertEquals(agent.getConversationHistory(),s.projection().messages());
            assertFalse(s.projection().activeSurface().stream().anyMatch(n->n.sequence()==seq));
            assertFalse(Files.readString(s.sessionDirectory().resolve("events.jsonl")).contains("a".repeat(64)));
            assertTrue(s.readAll().stream().noneMatch(e->e.payload().path("message").path("content").asText().startsWith("{\"source\":\"runtime_recovery_context\"")));
        }
    }

    @Test void noToolBudgetWrapupAlsoUsesRecoveryViewAndMatchingSnapshot() throws Exception {
        String prior=System.getProperty("codeagent.react.hard.max.iterations");
        System.setProperty("codeagent.react.hard.max.iterations","1");
        try(var store=SessionStore.open(dir.resolve("history"));var s=create(store)) {
            var call=new LlmClient.ToolCall("call",new LlmClient.ToolCall.Function("list_dir","{\"path\":\".\"}"));
            var client=new Client(List.of(new LlmClient.ChatResponse("assistant","reading",List.of(call),10,2),response("partial")));
            var agent=agent(client);agent.attachSession(s);
            RecoveryRequestAugmenterTest.start(s,"original",1);RecoveryRequestAugmenterTest.message(s,"original","user","goal",false);
            RecoveryRequestAugmenterTest.start(s,"control",2);long seq=RecoveryRequestAugmenterTest.message(s,"control","user","continue",true);RecoveryRequestAugmenterTest.message(s,"control","assistant","accepted",true);
            assertEquals(com.codeagent.runtime.execution.ExecutionOutcome.PARTIAL,agent.runExecution("goal","continue",context(s,"original","control",seq,false,"d")).outcome());
            assertEquals(2,client.messages.size());assertTrue(client.tools.get(1).isEmpty());assertSnapshotMatches(s,client);
            assertEquals(1,client.messages.get(1).stream().filter(m->"continue".equals(m.content())).count());
            assertFalse(Files.readString(s.sessionDirectory().resolve("events.jsonl")).contains("d".repeat(64)));
        } finally {if(prior==null)System.clearProperty("codeagent.react.hard.max.iterations");else System.setProperty("codeagent.react.hard.max.iterations",prior);}
    }

    @Test void finalAssistantWithoutEndCannotCauseSecondModelCallOrCorruptLedger() throws Exception {
        try(var store=SessionStore.open(dir.resolve("history"));var s=create(store)) {
            var client=new Client(List.of(response("must not run")));var agent=agent(client);agent.attachSession(s);
            RecoveryRequestAugmenterTest.start(s,"original",1);RecoveryRequestAugmenterTest.message(s,"original","user","goal",false);RecoveryRequestAugmenterTest.message(s,"original","assistant","saved final",false);
            RecoveryRequestAugmenterTest.start(s,"control",2);long seq=RecoveryRequestAugmenterTest.message(s,"control","user","continue",true);
            long before=s.projection().lastAppliedSequence();
            assertThrows(IllegalArgumentException.class,()->agent.runExecution("goal","continue",context(s,"original","control",seq,false,"e")));
            assertEquals(before,s.projection().lastAppliedSequence());assertTrue(client.messages.isEmpty());
        }
    }

    @Test void sendsAugmentedSnapshotWithExplicitResultOwnerThroughRealToolLoop() throws Exception {
        try (var store = SessionStore.open(dir.resolve("history")); var s = create(store)) {
            Files.writeString(dir.resolve("current-allowed.txt"),"synthetic");
            var call = new LlmClient.ToolCall("call", new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}"));
            var client = new Client(List.of(new LlmClient.ChatResponse("assistant", "reading", List.of(call), 10, 2), response("done")));
            var agent = agent(client); agent.attachSession(s);
            RecoveryRequestAugmenterTest.start(s,"original",1); RecoveryRequestAugmenterTest.message(s,"original","user","old goal",false);
            RecoveryRequestAugmenterTest.start(s,"question",2); RecoveryRequestAugmenterTest.message(s,"question","user","progress?",false); RecoveryRequestAugmenterTest.message(s,"question","assistant","earlier progress answer",false);
            RecoveryRequestAugmenterTest.start(s,"control",3); long seq=RecoveryRequestAugmenterTest.message(s,"control","user","list files in workspace",true); RecoveryRequestAugmenterTest.message(s,"control","assistant","accepted",true);
            var ctx=context(s,"original","control",seq,false,"f");
            try (var wrongThreadIdentity=ExecutionInteractionContext.install("control", s.sessionId())) {
                assertEquals(com.codeagent.runtime.execution.ExecutionOutcome.SUCCEEDED,agent.runExecution("old goal", "list files in workspace",ctx).outcome());
            }
            assertEquals(2,client.messages.size());
            for (int i=0;i<2;i++) {
                var actual=client.messages.get(i);
                assertEquals(1,actual.stream().filter(m->"list files in workspace".equals(m.content())).count());
                assertEquals(1,actual.stream().filter(m->"user".equals(m.role())&&m.content()!=null&&m.content().startsWith("{\"source\":\"runtime_recovery_context\"")).count());
                assertTrue(actual.stream().anyMatch(m->"earlier progress answer".equals(m.content())));
                assertFalse(actual.stream().anyMatch(m->"accepted".equals(m.content())));
                String captured=new RequestSnapshotFactory().capture(client,actual,client.tools.get(i),0).surfaceFingerprint();
                var snapshots=s.readAll().stream().filter(e->SessionEvent.Types.REQUEST_SNAPSHOT.equals(e.type())).toList();
                assertEquals(captured,snapshots.get(i).payload().path("surfaceFingerprint").asText());
            }
            assertTrue(client.messages.get(1).stream().anyMatch(m->"tool".equals(m.role())&&m.content()!=null&&m.content().contains("current-allowed.txt")));
            var requestEvents=s.readAll().stream().filter(e->SessionEvent.Types.REQUEST_STARTED.equals(e.type())).toList();
            assertTrue(requestEvents.stream().allMatch(e->"original".equals(e.payload().path("executionId").asText())));
            assertNotNull(s.projection().executionEnvelopes().get("original").assistantSequence());
            assertFalse(Files.readString(s.sessionDirectory().resolve("events.jsonl")).contains("f".repeat(64)));
        }
    }

    @Test void statusReplyRefusesUnexpectedModelToolsAndDoesNotPersistAnotherUser() throws Exception {
        try (var store=SessionStore.open(dir.resolve("history"));var s=create(store)) {
            var call=new LlmClient.ToolCall("call",new LlmClient.ToolCall.Function("list_dir","{\"path\":\".\"}"));
            var client=new Client(List.of(new LlmClient.ChatResponse("assistant","attempt",List.of(call),10,2)));
            var actualTools=new int[1];
            var registry=new ToolRegistry() {
                @Override public List<ToolExecutionResult> executeTools(List<ToolInvocation> calls) {
                    actualTools[0]++; return super.executeTools(calls);
                }
            }; registry.setProjectPath(dir.toString());
            var agent=new Agent(client,registry);agent.attachSession(s);
            RecoveryRequestAugmenterTest.start(s,"status",1);long seq=RecoveryRequestAugmenterTest.message(s,"status","user","what is the state?",false);
            agent.runExecution("what is the state?","what is the state?",context(s,"status","status",seq,true,"a"));
            assertTrue(client.tools.get(0).isEmpty());assertEquals(1,client.messages.size());assertEquals(0,actualTools[0]);
            assertEquals(1,s.readAll().stream().filter(e->SessionEvent.Types.USER_MESSAGE.equals(e.type())).count());
            assertTrue(s.projection().pendingTools().isEmpty());
            assertFalse(s.readAll().stream().anyMatch(e->SessionEvent.Types.TOOL_CALL.equals(e.type())));
        }
    }

    static RecoveryRequestContext context(SessionStore.SessionHandle s,String result,String input,long sequence,boolean status,String hash) {
        return new RecoveryRequestContext(new RecoveryContextSnapshot("workspace",s.sessionId(),1,s.projection().lastAppliedSequence(),hash.repeat(64),
                List.of(new RecoveryExecutionState(result,null,"REACT","RUNNING","ready",1,false,false,"old goal")),List.of(),List.of()),new RequestAssemblyBoundary(result,input,sequence),status);
    }
    private SessionStore.SessionHandle create(SessionStore store) throws Exception { return store.create(new SessionStore.SessionCreateRequest(dir,"test","test",null,"react","agent")); }
    private Agent agent(Client c) { var registry=new ToolRegistry();registry.setProjectPath(dir.toString());return new Agent(c,registry); }
    static LlmClient.ChatResponse response(String text) {return new LlmClient.ChatResponse("assistant",text,null,10,2);}
    private static void addAnswered(SessionStore.SessionHandle s,String id,long ordinal,String question,String answer)throws Exception {
        RecoveryRequestAugmenterTest.start(s,id,ordinal);RecoveryRequestAugmenterTest.message(s,id,"user",question,false);RecoveryRequestAugmenterTest.message(s,id,"assistant",answer,false);
    }
    private static void assertSnapshotMatches(SessionStore.SessionHandle s, Client client) {
        var snapshots=s.readAll().stream().filter(e->SessionEvent.Types.REQUEST_SNAPSHOT.equals(e.type())).toList();
        assertEquals(client.messages.size(),snapshots.size());
        for(int i=0;i<snapshots.size();i++)assertEquals(new RequestSnapshotFactory().capture(client,client.messages.get(i),client.tools.get(i),0).surfaceFingerprint(),snapshots.get(i).payload().path("surfaceFingerprint").asText());
    }
    static class Client implements LlmClient {
        final Deque<ChatResponse> responses; final List<List<Message>> messages=new ArrayList<>();final List<List<Tool>> tools=new ArrayList<>();
        final List<List<Message>> summaries=new ArrayList<>();boolean overflowOnce;
        Client(List<ChatResponse> responses){this.responses=new ArrayDeque<>(responses);}
        public ChatResponse chat(List<Message> m,List<Tool> t)throws IOException{return chat(m,t,StreamListener.NO_OP);}
        public ChatResponse chat(List<Message> m,List<Tool> t,StreamListener l)throws IOException{
            if(m.get(0).content().startsWith("你是一个对话摘要助手")){summaries.add(List.copyOf(m));if(responses.size()>1)return responses.remove();return response("summary");}
            messages.add(List.copyOf(m));tools.add(t==null?List.of():List.copyOf(t));
            if(overflowOnce){overflowOnce=false;throw new ContextWindowExceededException("test overflow");}
            return responses.remove();
        }
        public String getModelName(){return "test";}public String getProviderName(){return "test";}
    }
}
