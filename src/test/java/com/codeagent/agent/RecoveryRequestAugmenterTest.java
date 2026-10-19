package com.codeagent.agent;

import com.codeagent.history.*;
import com.codeagent.llm.LlmClient;
import com.codeagent.model.recovery.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryRequestAugmenterTest {
    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .disable(com.fasterxml.jackson.databind.MapperFeature.AUTO_DETECT_IS_GETTERS).build();
    @TempDir Path dir;
    @Test void exactSequenceDistinguishesIdenticalInputsPreservesHistoryAndToolSuffix() throws Exception {
        try (var store = SessionStore.open(dir.resolve("history")); var session = store.create(new SessionStore.SessionCreateRequest(dir, "test", "test", null, "react", "agent"))) {
            start(session, "original", 1); message(session, "original", "user", "original goal", false);
            start(session, "earlier", 2); message(session, "earlier", "user", "continue", false); message(session, "earlier", "assistant", "progress answer", false);
            start(session, "control", 3); long seq = message(session, "control", "user", "continue", true); message(session, "control", "assistant", "accepted", true);
            var boundary = new RequestAssemblyBoundary("original", "control", seq);
            var context = formatted();
            var augmenter = new RecoveryRequestAugmenter();
            var result = augmenter.augmentRequest(session.projection().messages(), context, boundary, session);
            assertEquals(List.of("original goal", "continue", "progress answer", context.content(), "continue"), result.stream().map(LlmClient.Message::content).toList());
            var suffix = new ArrayList<>(session.projection().messages()); suffix.add(LlmClient.Message.assistant("new response"));
            assertThrows(IllegalArgumentException.class, () -> augmenter.augmentRequest(suffix, context, boundary, session));
            assertTrue(session.readAll().stream().noneMatch(e -> e.payload().toString().contains("runtime_recovery_context")));
            assertThrows(IllegalArgumentException.class, () -> augmenter.augmentRequest(session.projection().messages(), context, new RequestAssemblyBoundary("original", "earlier", seq), session));
        }
    }

    @Test void rematerializesCompactedExactUserWithoutMutatingParent() throws Exception {
        try (var store = SessionStore.open(dir.resolve("history")); var session = store.create(new SessionStore.SessionCreateRequest(dir, "test", "test", null, "react", "agent"))) {
            start(session, "original", 1); message(session, "original", "user", "goal", false);
            start(session, "control", 2); long seq = message(session, "control", "user", "continue", true); message(session, "control", "assistant", "accepted", true);
            session.append(new SessionEventDraft(SessionEvent.Types.SURFACE_CLEAR, "react", "agent", "agent", false, SessionEvent.SurfaceOperation.clear(), JSON.createObjectNode()));
            long before = session.projection().lastAppliedSequence();
            var result = new RecoveryRequestAugmenter().augmentRequest(session.projection().messages(), formatted(), new RequestAssemblyBoundary("original", "control", seq), session);
            assertEquals(2, result.size()); assertEquals("continue", result.get(1).content());
            assertEquals(before, session.projection().lastAppliedSequence()); assertTrue(session.projection().messages().isEmpty());
        }
    }

    @Test void rematerializedCurrentUserPreservesItsExactAttachments() throws Exception {
        try (var store=SessionStore.open(dir.resolve("history"));var s=store.create(new SessionStore.SessionCreateRequest(dir,"test","test",null,"react","agent"))) {
            start(s,"original",1);message(s,"original","user","goal",false);start(s,"control",2);
            var current=LlmClient.Message.user(List.of(LlmClient.ContentPart.text("continue with this image"),LlmClient.ContentPart.imageBase64("synthetic-current-payload","image/png")));
            var payload=JSON.createObjectNode();payload.set("message",JSON.valueToTree(current));payload.putObject("conversation").put("executionId","control").put("mode","react").put("role","user").put("content","continue with this image");
            long seq=s.append(new SessionEventDraft(SessionEvent.Types.USER_MESSAGE,"react","continuation-control","continuation-control",false,SessionEvent.SurfaceOperation.append(),payload)).sequence();
            s.append(new SessionEventDraft(SessionEvent.Types.SURFACE_CLEAR,"react","agent","agent",false,SessionEvent.SurfaceOperation.clear(),JSON.createObjectNode()));
            var result=new RecoveryRequestAugmenter().augmentRequest(s.projection().messages(),formatted(),new RequestAssemblyBoundary("original","control",seq),s);
            assertEquals(current,result.get(result.size()-1));assertEquals(1,result.get(result.size()-1).imagePartCount());
            var enriched = new RecoveryRequestAugmenter("skill-prefix\n", "\nmemory-suffix")
                    .augmentRequest(s.projection().messages(), formatted(), new RequestAssemblyBoundary("original", "control", seq), s);
            var actual = enriched.get(enriched.size() - 1);
            assertEquals(1, actual.imagePartCount());
            assertTrue(actual.content().contains("skill-prefix"));
            assertTrue(actual.content().contains("memory-suffix"));
            assertEquals(current.contentParts().get(1), actual.contentParts().get(2));
        }
    }

    @Test void durableSummaryStaysBeforeCurrentInputAndOwnedInternalSuffix() throws Exception {
        try (var store = SessionStore.open(dir.resolve("history")); var s = store.create(new SessionStore.SessionCreateRequest(dir,"test","test",null,"react","agent"))) {
            appendInternal(s, LlmClient.Message.system("system"));
            start(s,"original",1); message(s,"original","user","old goal",false);
            start(s,"control",2); long seq=message(s,"control","user","CURRENT INPUT",true);
            message(s,"control","assistant","accepted",true);
            startRequest(s,"original","resumed-1");
            for(int i=1;i<=3;i++) appendInternal(s,LlmClient.Message.user("lsp diagnostic "+i));
            compactToSummary(s);
            var canonical=List.copyOf(s.projection().messages());
            long before=s.projection().lastAppliedSequence();
            var sent=new RecoveryRequestAugmenter().augmentRequest(canonical,formatted(),new RequestAssemblyBoundary("original","control",seq),s);
            assertEquals(List.of("system","HISTORICAL SUMMARY","SUMMARY ACK",formatted().content(),"CURRENT INPUT","lsp diagnostic 1","lsp diagnostic 2","lsp diagnostic 3"),sent.stream().map(LlmClient.Message::content).toList());
            assertEquals(1,s.projection().compactionGeneration());
            assertEquals(canonical,s.projection().messages()); assertEquals(before,s.projection().lastAppliedSequence());
            assertFalse(java.nio.file.Files.readString(s.sessionDirectory().resolve("events.jsonl")).contains("runtime_recovery_context"));
        }
    }

    static void startRequest(SessionStore.SessionHandle s,String executionId,String requestId) throws Exception {
        s.append(new SessionEventDraft(SessionEvent.Types.REQUEST_STARTED,"react","agent","agent",false,SessionEvent.SurfaceOperation.none(),JSON.createObjectNode().put("requestId",requestId).put("executionId",executionId)));
    }

    @Test void compactedInputPrecedesAnOwnedCompleteToolGroupWithoutInternalUserAnchor() throws Exception {
        try(var store=SessionStore.open(dir.resolve("history"));var s=store.create(new SessionStore.SessionCreateRequest(dir,"test","test",null,"react","agent"))) {
            appendInternal(s,LlmClient.Message.system("system"));start(s,"original",1);message(s,"original","user","old goal",false);
            start(s,"control",2);long seq=message(s,"control","user","continue",true);message(s,"control","assistant","accepted",true);
            startRequest(s,"original","owned-request");
            var call=new LlmClient.ToolCall("owned-call",new LlmClient.ToolCall.Function("list_dir","{}"));
            var assistant=new LlmClient.Message("assistant","reading",null,List.of(call),null);
            var p=JSON.createObjectNode().put("requestId","owned-request");p.set("message",JSON.valueToTree(assistant));
            s.append(new SessionEventDraft(SessionEvent.Types.ASSISTANT_MESSAGE,"react","agent","agent",false,SessionEvent.SurfaceOperation.append(),p));
            s.append(new SessionEventDraft(SessionEvent.Types.REQUEST_FINISHED,"react","agent","agent",false,SessionEvent.SurfaceOperation.none(),JSON.createObjectNode().put("requestId","owned-request").put("executionId","original")));
            var tool=LlmClient.Message.tool("owned-call","recorded output");
            p=JSON.createObjectNode().put("invocationId","owned-call");p.set("message",JSON.valueToTree(tool));
            s.append(new SessionEventDraft(SessionEvent.Types.TOOL_RESULT,"react","agent","agent",false,SessionEvent.SurfaceOperation.append(),p));
            appendInternal(s,LlmClient.Message.user("lsp diagnostic"));compactToSummary(s);
            var out=new RecoveryRequestAugmenter().augmentRequest(s.projection().messages(),formatted(),new RequestAssemblyBoundary("original","control",seq),s);
            assertEquals(List.of("system","HISTORICAL SUMMARY","SUMMARY ACK",formatted().content(),"continue","reading","recorded output","lsp diagnostic"),out.stream().map(LlmClient.Message::content).toList());
            assertEquals(assistant,out.get(5));assertEquals(tool,out.get(6));
        }
    }
    static void appendInternal(SessionStore.SessionHandle s,LlmClient.Message message) throws Exception {
        var p=JSON.createObjectNode();p.set("message",JSON.valueToTree(message));
        s.append(new SessionEventDraft("system".equals(message.role())?SessionEvent.Types.SYSTEM_MESSAGE:SessionEvent.Types.USER_MESSAGE,"react","agent","agent",false,SessionEvent.SurfaceOperation.append(),p));
    }
    static void compactToSummary(SessionStore.SessionHandle s) throws Exception {
        var parent=new ParentConversationContext(s.projection().messages().get(0));parent.setSessionHandle(s);parent.synchronizeProviderFromProjection();
        var canonical=s.projection().messages();var compacted=new ArrayList<LlmClient.Message>();
        compacted.add(canonical.get(0));compacted.add(LlmClient.Message.user("HISTORICAL SUMMARY"));compacted.add(LlmClient.Message.assistant("SUMMARY ACK"));
        compacted.addAll(canonical.subList(canonical.size()-3,canonical.size()));
        parent.commitPreparedCompaction(compacted,"react","agent","automatic");
    }
    static FormattedRecoveryContext formatted() { return new FormattedRecoveryContext("{\"source\":\"runtime_recovery_context\"}", "a".repeat(64), 15,0,0,0,0,0,0,RecoveryCompleteness.COMPLETE); }
    static void start(SessionStore.SessionHandle s, String id, long ordinal) throws Exception {
        s.append(new SessionEventDraft(SessionEvent.Types.EXECUTION_START, "runtime", "execution-coordinator", "runtime-execution", false, SessionEvent.SurfaceOperation.none(), JSON.createObjectNode().put("executionId",id).put("ordinal",ordinal)));
    }
    static long message(SessionStore.SessionHandle s,String id,String role,String content,boolean control) throws Exception {
        var p=JSON.createObjectNode(); p.set("message",JSON.valueToTree("user".equals(role)?LlmClient.Message.user(content):LlmClient.Message.assistant(content)));
        p.putObject("conversation").put("executionId",id).put("mode","react").put("role",role).put("content",content);
        return s.append(new SessionEventDraft("user".equals(role)?SessionEvent.Types.USER_MESSAGE:SessionEvent.Types.ASSISTANT_MESSAGE,"react",control?"continuation-control":"agent",control?"continuation-control":"agent",false,SessionEvent.SurfaceOperation.append(),p)).sequence();
    }
}
