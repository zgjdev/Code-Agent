package com.codeagent.history;

import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void createsAppendsAndResumesARealSession() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        String sessionId;
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(request(workspace))) {
            sessionId = handle.sessionId();
            SessionEvent appended = handle.append(messageDraft("hello"));

            assertEquals(1, appended.sequence());
            assertEquals(List.of("hello"), handle.projection().messages().stream()
                    .map(LlmClient.Message::content).toList());
            assertTrue(Files.exists(handle.sessionDirectory().resolve("manifest.json")));
            assertTrue(Files.exists(handle.sessionDirectory().resolve("events.jsonl")));
        }

        try (SessionStore reopened = SessionStore.open(tempDir);
             SessionStore.SessionHandle resumed = reopened.resumeWritable(sessionId, workspace)) {
            assertEquals(List.of("hello"), resumed.projection().messages().stream()
                    .map(LlmClient.Message::content).toList());
            assertEquals(2, resumed.append(messageDraft("again")).sequence());
        }
    }

    @Test
    void refusesSecondWriterForSameSession() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        try (SessionStore firstStore = SessionStore.open(tempDir);
             SessionStore.SessionHandle first = firstStore.create(request(workspace));
             SessionStore secondStore = SessionStore.open(tempDir)) {
            assertThrows(SessionStore.SessionLockedException.class,
                    () -> secondStore.resumeWritable(first.sessionId(), workspace));
        }
    }

    @Test
    void rejectsResumeFromDifferentWorkspace() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path other = Files.createDirectory(tempDir.resolve("other"));
        String id;
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(request(workspace))) {
            id = handle.sessionId();
        }
        try (SessionStore store = SessionStore.open(tempDir)) {
            assertThrows(SessionStore.WorkspaceMismatchException.class,
                    () -> store.resumeWritable(id, other));
        }
    }

    @Test
    void ignoresOnlyAnIncompleteFinalJsonLine() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        String id;
        Path events;
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(request(workspace))) {
            id = handle.sessionId();
            handle.append(messageDraft("committed"));
            events = handle.sessionDirectory().resolve("events.jsonl");
        }
        Files.writeString(events, "{\"schemaVersion\":2", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle resumed = store.resumeWritable(id, workspace)) {
            assertEquals(List.of("committed"), resumed.projection().messages().stream()
                    .map(LlmClient.Message::content).toList());
            assertTrue(resumed.projection().warnings().stream()
                    .anyMatch(value -> value.contains("incomplete final line")));
            assertEquals(2, resumed.append(messageDraft("after recovery")).sequence());
        }

        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle resumed = store.resumeWritable(id, workspace)) {
            assertEquals(List.of("committed", "after recovery"), resumed.projection().messages().stream()
                    .map(LlmClient.Message::content).toList());
        }
    }

    @Test
    void rejectsCorruptJsonBeforeTheFinalLine() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        String id;
        Path events;
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(request(workspace))) {
            id = handle.sessionId();
            events = handle.sessionDirectory().resolve("events.jsonl");
        }
        Files.writeString(events, "not-json\n{}\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        try (SessionStore store = SessionStore.open(tempDir)) {
            assertThrows(SessionStore.CorruptSessionLogException.class,
                    () -> store.resumeWritable(id, workspace));
        }
    }

    @Test
    void listsLatestUnclosedAndMarksSessionClosed() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(request(workspace))) {
            assertEquals(handle.sessionId(), store.latestUnclosed(workspace).orElseThrow().sessionId());
            handle.markClosed("exit");
            assertFalse(store.latestUnclosed(workspace).isPresent());
            assertEquals(handle.sessionId(), store.latest(workspace).orElseThrow().sessionId());
            assertTrue(store.list(workspace, 20).get(0).closed());
            assertTrue(handle.projection().cleanlyClosed());
        }
    }

    @Test
    void resumeClosesInterruptedRequestsAndPairsPendingToolCalls() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        String id;
        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle handle = store.create(request(workspace))) {
            id = handle.sessionId();
            handle.append(lifecycle(SessionEvent.Types.REQUEST_STARTED, "request-1"));
            ObjectNode assistant = JSON.createObjectNode().put("requestId", "request-1");
            assistant.set("message", JSON.valueToTree(LlmClient.Message.assistant(
                    "calling", List.of(new LlmClient.ToolCall("call-1",
                            new LlmClient.ToolCall.Function("write_file", "{}"))))));
            handle.append(new SessionEventDraft(SessionEvent.Types.ASSISTANT_MESSAGE,
                    "react", "agent", "test", false,
                    SessionEvent.SurfaceOperation.append(), assistant));
            handle.append(lifecycle(SessionEvent.Types.REQUEST_FINISHED, "request-1"));
            ObjectNode tool = JSON.createObjectNode().put("invocationId", "call-1")
                    .put("name", "write_file").put("arguments", "{}");
            handle.append(new SessionEventDraft(SessionEvent.Types.TOOL_CALL,
                    "react", "agent", "test", false,
                    SessionEvent.SurfaceOperation.none(), tool));
            handle.append(lifecycle(SessionEvent.Types.REQUEST_STARTED, "request-2"));
        }

        try (SessionStore store = SessionStore.open(tempDir);
             SessionStore.SessionHandle resumed = store.resumeWritable(id, workspace)) {
            assertTrue(resumed.projection().incompleteRequestIds().isEmpty());
            assertTrue(resumed.projection().pendingTools().isEmpty());
            assertEquals(List.of("assistant", "tool"), resumed.projection().messages().stream()
                    .map(LlmClient.Message::role).toList());
            assertTrue(resumed.projection().messages().get(1).content().contains("not retried"));
            assertTrue(resumed.resumeResult().interrupted());
            assertEquals(1, resumed.resumeResult().recoveredToolCalls());
        }
    }

    private SessionStore.SessionCreateRequest request(Path workspace) {
        return new SessionStore.SessionCreateRequest(workspace, "deepseek", "deepseek-chat",
                null, "react", "agent");
    }

    private SessionEventDraft messageDraft(String text) {
        ObjectNode payload = JSON.createObjectNode();
        payload.set("message", JSON.valueToTree(LlmClient.Message.user(text)));
        return new SessionEventDraft(SessionEvent.Types.USER_MESSAGE, "react", "agent", "test",
                false, SessionEvent.SurfaceOperation.append(), payload);
    }

    private SessionEventDraft lifecycle(String type, String requestId) {
        return new SessionEventDraft(type, "react", "agent", "test", false,
                SessionEvent.SurfaceOperation.none(),
                JSON.createObjectNode().put("requestId", requestId));
    }
}
