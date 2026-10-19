package com.codeagent.history;

import com.codeagent.agent.Agent;
import com.codeagent.agent.PlanConversationReconciler;
import com.codeagent.llm.LlmClient;
import com.codeagent.plan.ExecutionPlan;
import com.codeagent.plan.PlanStateStore;
import com.codeagent.plan.Task;
import com.codeagent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class SessionTranscriptReaderTest {
    static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .disable(com.fasterxml.jackson.databind.MapperFeature.AUTO_DETECT_IS_GETTERS).build();
    @TempDir Path temp;

    @Test void narrowControlSourceIsVisibleButForgedActorStaysHidden() throws Exception {
        try (var store=SessionStore.open(temp.resolve("history"));var s=create(store,temp)) {
            var original = message("user", "continue control", null);
            s.append(new SessionEventDraft(original.type(),"react","continuation-control","continuation-control",false,original.surface(),original.payload()));
            var receipt = message("assistant", "control accepted", null);
            s.append(new SessionEventDraft(receipt.type(),"react","continuation-control","continuation-control",false,receipt.surface(),receipt.payload()));
            s.append(new SessionEventDraft(receipt.type(),"react","other-control","continuation-control",false,receipt.surface(),receipt.payload()));
            var page=new SessionTranscriptReader(store).readPage(s.sessionId(),temp,-1,30,-1);
            assertEquals(List.of("continue control","control accepted"),page.entries().stream().map(TranscriptEntry::text).toList());
        }
    }

    @Test
    void projectsActualReactPlanAndCompactionEventsInsteadOfCurrentContext() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history")); var handle = create(store, workspace)) {
            QueueClient client = new QueueClient("react answer", "after plan answer", "summary");
            ToolRegistry tools = new ToolRegistry();
            tools.setProjectPath(workspace.toString());
            Agent react = new Agent(client, tools);
            react.attachSession(handle);
            react.run("react question");

            ParentConversationContext context = react.getParentConversationContext();
            PlanStateStore plans = new PlanStateStore(temp.resolve("plans.db"));
            ExecutionPlan plan = new ExecutionPlan("plan-one", "plan goal");
            Task task = new Task("task_1", "work", Task.TaskType.ANALYSIS);
            plan.addTask(task);
            plans.savePlan(workspace, handle.sessionId(), "plan question", plan);
            PlanConversationReconciler reconciler = new PlanConversationReconciler();
            reconciler.reconcile(plans, context, workspace, handle.sessionId());
            plan.markStarted();
            plans.checkpointPlan(plan);
            task.markCompleted("plan result");
            plans.checkpointTask(plan.getId(), task);
            plan.markCompleted();
            plans.checkpointPlan(plan);
            reconciler.reconcile(plans, context, workspace, handle.sessionId());

            react.run("after plan question");
            assertTrue(react.compactHistoryNow().compacted());
            SessionTranscriptReader reader = new SessionTranscriptReader(store);
            var page = reader.readPage(handle.sessionId(), workspace, -1, 30, -1);
            var messages = page.entries().stream().filter(e -> e.boundaryKind() == TranscriptEntry.BoundaryKind.MESSAGE).toList();
            assertEquals(List.of("react question", "react answer", "plan question"),
                    messages.subList(0, 3).stream().map(TranscriptEntry::text).toList());
            assertTrue(messages.get(3).text().contains("plan result"));
            assertEquals(List.of("after plan question", "after plan answer"),
                    messages.subList(4, 6).stream().map(TranscriptEntry::text).toList());
            assertEquals(6, messages.size());
            assertEquals(1, page.entries().stream().filter(e -> e.boundaryKind() == TranscriptEntry.BoundaryKind.COMPACTION).count());
            assertFalse(page.entries().stream().anyMatch(e -> e.text().equals("summary")));
        }
    }

    @Test
    void clearRequiresOptInForOlderHistoryAndBrowsingNeverAppends() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history")); var handle = create(store, workspace)) {
            Agent react = new Agent(new QueueClient("old answer", "new answer"), new ToolRegistry());
            react.attachSession(handle);
            react.run("old question");
            react.clearHistory();
            react.run("new question");
            var file = handle.sessionDirectory().resolve("events.jsonl");
            byte[] before = Files.readAllBytes(file);
            SessionTranscriptReader reader = new SessionTranscriptReader(store);
            var current = reader.readPage(handle.sessionId(), workspace, -1, 30, -1);
            assertEquals(List.of(TranscriptEntry.BoundaryKind.CLEAR, TranscriptEntry.BoundaryKind.MESSAGE,
                    TranscriptEntry.BoundaryKind.MESSAGE), current.entries().stream().map(TranscriptEntry::boundaryKind).toList());
            assertEquals("new question", current.entries().get(1).text());
            var full = reader.readPage(handle.sessionId(), workspace, TranscriptCursor.start(), 30,
                    current.snapshotLastSequence(), true);
            assertEquals(5, full.entries().size());
            assertArrayEquals(before, Files.readAllBytes(file));
        }
    }

    @Test
    void requestCommitIsSnapshotBoundAndIncompleteAnswerIsExplicitFragment() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history")); var handle = create(store, workspace)) {
            handle.append(message("user", "question", null));
            handle.append(lifecycle(SessionEvent.Types.REQUEST_STARTED, "r1"));
            handle.append(message("assistant", "saved fragment", "r1"));
            SessionTranscriptReader reader = new SessionTranscriptReader(store);
            var pending = reader.readPage(handle.sessionId(), workspace, -1, 30, -1);
            assertEquals(TranscriptEntry.BoundaryKind.INTERRUPTED_FRAGMENT, pending.entries().get(1).boundaryKind());
            handle.append(lifecycle(SessionEvent.Types.REQUEST_FINISHED, "r1"));
            assertEquals(TranscriptEntry.BoundaryKind.INTERRUPTED_FRAGMENT,
                    reader.readPage(handle.sessionId(), workspace, -1, 30, pending.snapshotLastSequence()).entries().get(1).boundaryKind());
            assertEquals(TranscriptEntry.BoundaryKind.MESSAGE,
                    reader.readPage(handle.sessionId(), workspace, -1, 30, -1).entries().get(1).boundaryKind());
            handle.append(lifecycle(SessionEvent.Types.REQUEST_STARTED, "r2"));
            handle.append(lifecycle(SessionEvent.Types.REQUEST_FAILED, "r2"));
            var failed = reader.readPage(handle.sessionId(), workspace, -1, 30, -1);
            assertEquals(TranscriptEntry.BoundaryKind.INTERRUPTED, failed.entries().get(2).boundaryKind());
            assertTrue(failed.entries().get(2).text().contains("未保存完整回答"));
        }
    }

    @Test
    void filtersNonConversationChildActorsSecretsTerminalControlsAndImagePayloads() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history")); var handle = create(store, workspace)) {
            var user = message("user", "你好😀\u001b[31mred\u001b[0m\u001b]0;owned\u0007 API_KEY=secretvalue", null);
            ((ObjectNode) user.payload()).set("message", JSON.valueToTree(LlmClient.Message.user(List.of(
                    LlmClient.ContentPart.text("different provider text"),
                    LlmClient.ContentPart.imageBase64("not-for-display", "image/png")))));
            handle.append(user);
            ObjectNode forged = (ObjectNode) message("assistant", "child hidden", null).payload();
            handle.append(new SessionEventDraft(SessionEvent.Types.ASSISTANT_MESSAGE, "react", "task:one", "agent", false,
                    SessionEvent.SurfaceOperation.none(), forged));
            ObjectNode hidden = JSON.createObjectNode();
            hidden.set("message", JSON.valueToTree(LlmClient.Message.assistant("hidden reasoning", "hidden surface")));
            handle.append(new SessionEventDraft(SessionEvent.Types.ASSISTANT_MESSAGE, "react", "agent", "agent", false,
                    SessionEvent.SurfaceOperation.append(), hidden));
            var page = new SessionTranscriptReader(store).readPage(handle.sessionId(), workspace, -1, 30, -1);
            assertEquals(1, page.entries().size());
            String text = page.entries().get(0).text();
            assertTrue(text.contains("你好😀red"));
            assertTrue(text.contains("[图片]"));
            assertFalse(text.contains("secretvalue"));
            assertFalse(text.contains("owned"));
            assertFalse(text.contains("not-for-display"));
            assertFalse(text.contains("\u001b"));
            assertFalse(page.toString().contains("你好"));
            assertFalse(page.entries().get(0).toString().contains("你好"));
        }
    }

    @Test
    void chunksAllLongUnicodeMessagesWithStableSnapshotAndNoDuplication() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history")); var handle = create(store, workspace)) {
            String longText = "中文😀!\n".repeat(3000);
            handle.append(message("user", longText, null));
            handle.append(message("assistant", "old answer", null));
            SessionTranscriptReader reader = new SessionTranscriptReader(store);
            var first = reader.readPage(handle.sessionId(), workspace, TranscriptCursor.start(), 1, -1, false);
            handle.append(message("user", "new after snapshot", null));
            List<TranscriptEntry> entries = new ArrayList<>(first.entries());
            var cursor = first.nextCursor();
            int guard = 0;
            while (cursor != null) {
                assertTrue(guard++ < 30);
                var page = reader.readPage(handle.sessionId(), workspace, cursor, 1, first.snapshotLastSequence(), false);
                entries.addAll(page.entries());
                cursor = page.nextCursor();
            }
            assertEquals(longText, entries.stream().filter(e -> e.role() == TranscriptEntry.Role.USER)
                    .map(TranscriptEntry::text).reduce("", String::concat));
            assertEquals("old answer", entries.get(entries.size() - 1).text());
            assertFalse(entries.stream().anyMatch(e -> e.text().contains("new after snapshot")));
            assertTrue(entries.stream().allMatch(e -> e.text().codePointCount(0, e.text().length()) <= SessionTranscriptReader.CHUNK_CODE_POINTS));
        }
    }

    static SessionStore.SessionHandle create(SessionStore store, Path workspace) throws IOException {
        return store.create(new SessionStore.SessionCreateRequest(workspace, "test", "model", null, "react", "agent"));
    }

    static SessionEventDraft message(String role, String content, String requestId) {
        ObjectNode payload = JSON.createObjectNode();
        payload.putObject("conversation").put("mode", "react").put("role", role).put("content", content);
        payload.set("message", JSON.valueToTree("user".equals(role) ? LlmClient.Message.user(content) : LlmClient.Message.assistant(content)));
        if (requestId != null) payload.put("requestId", requestId);
        return new SessionEventDraft("user".equals(role) ? SessionEvent.Types.USER_MESSAGE : SessionEvent.Types.ASSISTANT_MESSAGE,
                "react", "agent", "agent", false, SessionEvent.SurfaceOperation.append(), payload);
    }

    static SessionEventDraft lifecycle(String type, String requestId) {
        return new SessionEventDraft(type, "react", "agent", "agent", false, SessionEvent.SurfaceOperation.none(),
                JSON.createObjectNode().put("requestId", requestId));
    }

    static final class QueueClient implements LlmClient {
        private final Queue<String> answers;
        QueueClient(String... answers) { this.answers = new ArrayDeque<>(List.of(answers)); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            if (answers.isEmpty()) throw new IOException("unexpected call");
            return new ChatResponse("assistant", answers.remove(), null, null, 100_000, 10);
        }
        @Override public String getModelName() { return "test-model"; }
        @Override public String getProviderName() { return "test"; }
        @Override public boolean supportsTools() { return false; }
    }

    @Test
    void defaultThirtyEntryPagingPreservesSequenceAndRejectsUnboundChunkCursor() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history")); var handle = create(store, workspace)) {
            for (int i = 0; i < 35; i++) handle.append(message("user", "message " + i, null));
            var reader = new SessionTranscriptReader(store);
            var first = reader.readPage(handle.sessionId(), workspace, -1, 0, -1);
            assertEquals(30, first.entries().size());
            var second = reader.readPage(handle.sessionId(), workspace, first.nextCursor(), 30, first.snapshotLastSequence(), false);
            assertEquals(5, second.entries().size());
            assertEquals(31, second.entries().get(0).sequence());
            assertNull(second.nextCursor());
            assertThrows(IllegalArgumentException.class, () -> reader.readPage(handle.sessionId(), workspace,
                    new TranscriptCursor(0, 4096), 30, first.snapshotLastSequence(), false));
        }
    }

    @Test
    void emptySanitizedPendingBodyKeepsInterruptionNoticeAndRequestSourceCannotCommitAnswer() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history")); var handle = create(store, workspace)) {
            handle.append(message("user", "question", null));
            handle.append(lifecycle(SessionEvent.Types.REQUEST_STARTED, "r1"));
            handle.append(message("assistant", "\u001b[0m", "r1"));
            var reader = new SessionTranscriptReader(store);
            var pending = reader.readPage(handle.sessionId(), workspace, -1, 30, -1);
            assertEquals(TranscriptEntry.BoundaryKind.INTERRUPTED, pending.entries().get(1).boundaryKind());
            handle.append(lifecycle(SessionEvent.Types.REQUEST_FAILED, "r1"));
            handle.append(lifecycle(SessionEvent.Types.REQUEST_STARTED, "r2"));
            handle.append(message("assistant", "persisted body", "r2"));
            handle.append(new SessionEventDraft(SessionEvent.Types.REQUEST_FINISHED, "react", "task:one", "agent", false,
                    SessionEvent.SurfaceOperation.none(), JSON.createObjectNode().put("requestId", "r2")));
            var page = reader.readPage(handle.sessionId(), workspace, -1, 30, -1);
            assertEquals(TranscriptEntry.BoundaryKind.INTERRUPTED_FRAGMENT, page.entries().get(2).boundaryKind());
        }
    }

    @Test
    void unfinishedActualPlanTurnHasExplicitMissingAnswerBoundary() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history")); var handle = create(store, workspace)) {
            var context = new ParentConversationContext(LlmClient.Message.system("private system"));
            context.setSessionHandle(handle);
            var plans = new PlanStateStore(temp.resolve("plans.db"));
            var plan = new ExecutionPlan("pending-plan", "pending goal");
            plan.addTask(new Task("task_1", "work", Task.TaskType.ANALYSIS));
            plans.savePlan(workspace, handle.sessionId(), "pending user question", plan);
            new PlanConversationReconciler().reconcile(plans, context, workspace, handle.sessionId());
            var page = new SessionTranscriptReader(store).readPage(handle.sessionId(), workspace, -1, 30, -1);
            assertEquals(2, page.entries().size());
            assertEquals(TranscriptEntry.BoundaryKind.INTERRUPTED, page.entries().get(0).boundaryKind());
            assertTrue(page.entries().get(0).text().contains("未保存完整回答"));
            assertEquals("pending user question", page.entries().get(1).text());
            assertTrue(page.entries().stream().noneMatch(e -> e.role() == TranscriptEntry.Role.ASSISTANT));
        }
    }

    @Test
    void reactExecutionInterruptedBeforeRequestHasSnapshotBoundNoticeAndClearOptIn() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history")); var handle = create(store, workspace)) {
            handle.append(execution(SessionEvent.Types.EXECUTION_START, "before-request", 1));
            handle.append(executionMessage("user", "interrupted question", null, "before-request"));
            var reader = new SessionTranscriptReader(store);
            var interrupted = reader.readPage(handle.sessionId(), workspace, -1, 30, -1);
            assertEquals(2, interrupted.entries().size());
            assertEquals(TranscriptEntry.Role.CONTEXT, interrupted.entries().get(0).role());
            assertEquals(TranscriptEntry.BoundaryKind.INTERRUPTED, interrupted.entries().get(0).boundaryKind());
            assertEquals("本轮中断，未保存完整回答", interrupted.entries().get(0).text());
            assertEquals("interrupted question", interrupted.entries().get(1).text());

            handle.append(lifecycle(SessionEvent.Types.REQUEST_STARTED, "after-snapshot"));
            handle.append(executionMessage("assistant", "completed answer", "after-snapshot", "before-request"));
            handle.append(lifecycle(SessionEvent.Types.REQUEST_FINISHED, "after-snapshot"));
            handle.append(execution(SessionEvent.Types.EXECUTION_END, "before-request", 1));
            var fixed = reader.readPage(handle.sessionId(), workspace, -1, 30, interrupted.snapshotLastSequence());
            assertEquals(interrupted.entries(), fixed.entries());
            assertTrue(reader.readPage(handle.sessionId(), workspace, -1, 30, -1).entries().stream()
                    .noneMatch(e -> e.boundaryKind() == TranscriptEntry.BoundaryKind.INTERRUPTED));

            handle.append(new SessionEventDraft(SessionEvent.Types.SURFACE_CLEAR, "react", "agent", "agent", false,
                    SessionEvent.SurfaceOperation.clear(), JSON.createObjectNode()));
            var current = reader.readPage(handle.sessionId(), workspace, -1, 30, -1);
            assertEquals(1, current.entries().size());
            var older = reader.readPage(handle.sessionId(), workspace, TranscriptCursor.start(), 30,
                    current.snapshotLastSequence(), true);
            assertEquals(List.of("interrupted question", "completed answer"), older.entries().stream()
                    .filter(e -> e.boundaryKind() == TranscriptEntry.BoundaryKind.MESSAGE).map(TranscriptEntry::text).toList());
        }
    }

    @Test
    void reactExecutionFailedBeforeRequestKeepsNoticeWhenOlderHistoryIsExpanded() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history")); var handle = create(store, workspace)) {
            handle.append(execution(SessionEvent.Types.EXECUTION_START, "failed-before-request", 1));
            handle.append(executionMessage("user", "failed question", null, "failed-before-request"));
            var end = execution(SessionEvent.Types.EXECUTION_END, "failed-before-request", 1);
            ((ObjectNode) end.payload()).put("status", "failed").put("outcome", "failed");
            handle.append(end);
            var reader = new SessionTranscriptReader(store);
            var failed = reader.readPage(handle.sessionId(), workspace, -1, 30, -1);
            assertEquals(List.of(TranscriptEntry.BoundaryKind.MESSAGE, TranscriptEntry.BoundaryKind.INTERRUPTED),
                    failed.entries().stream().map(TranscriptEntry::boundaryKind).toList());
            assertEquals(3, failed.entries().get(1).sequence());
            handle.append(new SessionEventDraft(SessionEvent.Types.SURFACE_CLEAR, "react", "agent", "agent", false,
                    SessionEvent.SurfaceOperation.clear(), JSON.createObjectNode()));
            var current = reader.readPage(handle.sessionId(), workspace, -1, 30, -1);
            assertEquals(1, current.entries().size());
            var older = reader.readPage(handle.sessionId(), workspace, TranscriptCursor.start(), 30,
                    current.snapshotLastSequence(), true);
            assertEquals(1, older.entries().stream().filter(e -> e.boundaryKind() == TranscriptEntry.BoundaryKind.INTERRUPTED).count());
            assertEquals(failed.entries(), older.entries().subList(0, 2));
        }
    }

    static SessionEventDraft execution(String type, String executionId, long ordinal) {
        ObjectNode payload = JSON.createObjectNode().put("executionId", executionId);
        if (SessionEvent.Types.EXECUTION_START.equals(type)) payload.put("ordinal", ordinal).put("explicitMode", "react");
        else payload.put("selectedMode", "react").put("outcome", "succeeded").put("status", "completed");
        return new SessionEventDraft(type, "runtime", "runtime", "execution-finalizer", false,
                SessionEvent.SurfaceOperation.none(), payload);
    }

    static SessionEventDraft executionMessage(String role, String content, String requestId, String executionId) {
        var draft = message(role, content, requestId);
        ((ObjectNode) draft.payload().get("conversation")).put("executionId", executionId);
        return draft;
    }

    @Test
    void executionNoticesDeduplicateOnlyExplicitRequestAndFragmentIdentities() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history")); var handle = create(store, workspace)) {
            handle.append(execution(SessionEvent.Types.EXECUTION_START, "failed", 1));
            handle.append(executionMessage("user", "failed question", null, "failed"));
            for (String requestId : List.of("retry-one", "retry-two")) {
                var started = lifecycle(SessionEvent.Types.REQUEST_STARTED, requestId);
                ((ObjectNode) started.payload()).put("executionId", "failed");
                handle.append(started);
                handle.append(lifecycle(SessionEvent.Types.REQUEST_FAILED, requestId));
            }
            var end = execution(SessionEvent.Types.EXECUTION_END, "failed", 1);
            ((ObjectNode) end.payload()).put("status", "failed").put("outcome", "failed");
            handle.append(end);
            handle.append(execution(SessionEvent.Types.EXECUTION_START, "fragment", 2));
            handle.append(executionMessage("user", "fragment question", null, "fragment"));
            handle.append(lifecycle(SessionEvent.Types.REQUEST_STARTED, "fragment-request"));
            handle.append(executionMessage("assistant", "saved partial answer", "fragment-request", "fragment"));
            var reader = new SessionTranscriptReader(store);
            var first = reader.readPage(handle.sessionId(), workspace, TranscriptCursor.start(), 1, -1, false);
            List<TranscriptEntry> entries = new ArrayList<>(first.entries());
            var cursor = first.nextCursor();
            while (cursor != null) {
                var page = reader.readPage(handle.sessionId(), workspace, cursor, 1, first.snapshotLastSequence(), false);
                entries.addAll(page.entries());
                cursor = page.nextCursor();
            }
            assertEquals(List.of(TranscriptEntry.BoundaryKind.MESSAGE, TranscriptEntry.BoundaryKind.INTERRUPTED,
                            TranscriptEntry.BoundaryKind.MESSAGE, TranscriptEntry.BoundaryKind.INTERRUPTED_FRAGMENT),
                    entries.stream().map(TranscriptEntry::boundaryKind).toList());
            assertEquals(entries.stream().map(TranscriptEntry::sequence).sorted().toList(),
                    entries.stream().map(TranscriptEntry::sequence).toList());
            assertEquals(entries.size(), entries.stream().map(TranscriptEntry::sequence).distinct().count());
        }
    }

    @Test
    void unknownRequestIdentityIsNotInferredFromLatestExecutionUser() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history")); var handle = create(store, workspace)) {
            handle.append(execution(SessionEvent.Types.EXECUTION_START, "original", 1));
            handle.append(executionMessage("user", "original question", null, "original"));
            handle.append(execution(SessionEvent.Types.EXECUTION_START, "control", 2));
            handle.append(executionMessage("user", "control question", null, "control"));
            handle.append(executionMessage("assistant", "control acknowledgement", null, "control"));
            handle.append(execution(SessionEvent.Types.EXECUTION_END, "control", 2));
            handle.append(lifecycle(SessionEvent.Types.REQUEST_STARTED, "unknown-request"));
            var page = new SessionTranscriptReader(store).readPage(handle.sessionId(), workspace, -1, 30, -1);
            assertEquals(2, page.entries().stream().filter(e -> e.boundaryKind() == TranscriptEntry.BoundaryKind.INTERRUPTED).count());
            assertEquals(List.of(1L, 7L), page.entries().stream()
                    .filter(e -> e.boundaryKind() == TranscriptEntry.BoundaryKind.INTERRUPTED).map(TranscriptEntry::sequence).toList());
        }
    }
}
