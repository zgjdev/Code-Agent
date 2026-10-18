package com.codeagent.history;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionResumeServiceTest {
    @TempDir Path temp;

    @Test
    void authoritativeWaitingInputMakesStartOnlyParentAttachableButNotUnsafe() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (var store = SessionStore.open(temp.resolve("history"));
             var parent = SessionTranscriptReaderTest.create(store, workspace);
             var unsafe = store.createLegacy(new SessionStore.SessionCreateRequest(workspace,
                     "test", "model", null, "react", "agent"), "sha", true)) {
            var service = new SessionResumeService(store,
                    (id, path) -> new SessionResumeService.RecoverySummary(2, false));
            assertTrue(service.requireAttachable(parent.sessionId(), workspace).attachable());
            assertEquals(parent.sessionId(), service.latestSafeNonemptyParent(workspace)
                    .orElseThrow().summary().sessionId());
            assertEquals(1, service.list(workspace, 0, 20, "").stream().filter(SessionListEntry::attachable).count());
            assertThrows(IOException.class, () -> service.requireAttachable(unsafe.sessionId(), workspace));
            assertFalse(new SessionResumeService(store).list(workspace, 0, 20, "").stream()
                    .anyMatch(SessionListEntry::attachable));
        }
    }

    @Test
    void listsOnlyParentsAndLastAcceptsClosedSafeNonemptySessionWithoutAcquiringWriter() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path other = Files.createDirectory(temp.resolve("other"));
        try (SessionStore store = SessionStore.open(temp.resolve("history"));
             var parent = SessionTranscriptReaderTest.create(store, workspace);
             var child = parent.createChild("plan", "task:one");
             var foreign = SessionTranscriptReaderTest.create(store, other)) {
            parent.append(SessionTranscriptReaderTest.message("user", "first title\nsecond line", null));
            parent.markClosed("exit");
            child.append(SessionTranscriptReaderTest.message("user", "child title", null));
            foreign.append(SessionTranscriptReaderTest.message("user", "foreign title", null));
            AtomicInteger lookups = new AtomicInteger();
            SessionResumeService service = new SessionResumeService(store, (id, path) -> {
                lookups.incrementAndGet();
                assertEquals(parent.sessionId(), id);
                assertEquals(workspace.toRealPath(), path);
                return new SessionResumeService.RecoverySummary(2, true);
            });
            byte[] before = Files.readAllBytes(parent.sessionDirectory().resolve("events.jsonl"));
            List<SessionListEntry> entries = service.list(workspace, 0, 20, "first");
            assertEquals(1, entries.size());
            assertEquals("first title", entries.get(0).title());
            assertEquals(2, entries.get(0).waitingExecutionCount());
            assertTrue(entries.get(0).hasUnfinishedPlan());
            assertTrue(entries.get(0).attachable());
            assertTrue(entries.get(0).summary().closed());
            assertEquals(parent.sessionId(), service.latestSafeNonemptyParent(workspace).orElseThrow().summary().sessionId());
            assertEquals(parent.sessionId(), service.requireAttachable(parent.sessionId(), workspace).summary().sessionId());
            assertThrows(IOException.class, () -> service.requireAttachable(child.sessionId(), workspace));
            assertThrows(IOException.class, () -> service.requireAttachable(parent.sessionId(), other));
            assertArrayEquals(before, Files.readAllBytes(parent.sessionDirectory().resolve("events.jsonl")));
            assertTrue(lookups.get() >= 3);
            assertThrows(SessionStore.SessionLockedException.class,
                    () -> store.resumeWritable(parent.sessionId(), workspace));
        }
    }

    @Test
    void disablesEmptyUnsafeAndCorruptCandidatesWithSharedLastEligibility() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history"));
             var empty = SessionTranscriptReaderTest.create(store, workspace);
             var unsafe = store.createLegacy(new SessionStore.SessionCreateRequest(workspace, "test", "model", null, "react", "agent"), "sha", true);
             var corrupt = SessionTranscriptReaderTest.create(store, workspace)) {
            unsafe.append(SessionTranscriptReaderTest.message("user", "unsafe title", null));
            Files.writeString(corrupt.sessionDirectory().resolve("events.jsonl"), "private bad-json\n", StandardOpenOption.APPEND);
            SessionResumeService service = new SessionResumeService(store);
            var entries = service.list(workspace, 0, 20, "");
            assertEquals(3, entries.size());
            assertTrue(entries.stream().noneMatch(SessionListEntry::attachable));
            assertEquals(List.of(SessionListEntry.Availability.CORRUPT, SessionListEntry.Availability.EMPTY, SessionListEntry.Availability.UNSAFE).stream().sorted().toList(),
                    entries.stream().map(SessionListEntry::availability).sorted().toList());
            assertTrue(service.latestSafeNonemptyParent(workspace).isEmpty());
            assertThrows(IOException.class, () -> service.requireAttachable(empty.sessionId(), workspace));
            assertThrows(IOException.class, () -> service.requireAttachable(unsafe.sessionId(), workspace));
            var failure = assertThrows(IOException.class, () -> service.requireAttachable(corrupt.sessionId(), workspace));
            assertFalse(failure.toString().contains("private"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void pagesAndSearchesSanitizedFirstTopLevelTitleWithNoStatefulCancel() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history"))) {
            for (int i = 0; i < 23; i++) {
                try (var handle = SessionTranscriptReaderTest.create(store, workspace)) {
                    ObjectNode hidden = SessionTranscriptReaderTest.JSON.createObjectNode();
                    hidden.set("message", SessionTranscriptReaderTest.JSON.valueToTree(com.codeagent.llm.LlmClient.Message.user("hidden provider")));
                    handle.append(new SessionEventDraft(SessionEvent.Types.USER_MESSAGE, "react", "agent", "agent", false,
                            SessionEvent.SurfaceOperation.append(), hidden));
                    handle.append(SessionTranscriptReaderTest.message("user", "标题" + i + " \u001b]0;evil\u0007API_KEY=secretvalue\nsecond", null));
                    handle.append(SessionTranscriptReaderTest.message("user", "later title", null));
                    ObjectNode manifest = SessionTranscriptReaderTest.JSON.valueToTree(handle.manifest());
                    manifest.put("updatedAt", 1000 + i);
                    Files.writeString(handle.sessionDirectory().resolve("manifest.json"), manifest.toString());
                }
            }
            SessionResumeService service = new SessionResumeService(store);
            var first = service.list(workspace, 0, 0, "");
            var last = service.list(workspace, 20, 20, "");
            assertEquals(20, first.size());
            assertEquals(3, last.size());
            assertTrue(first.get(0).title().startsWith("标题22"));
            assertEquals(1, service.list(workspace, 0, 20, "标题22").size());
            assertEquals(0, service.list(workspace, 0, 20, "hidden provider").size());
            assertTrue(first.stream().allMatch(e -> !e.title().contains("secretvalue") && !e.title().contains("evil")));
            assertFalse(first.get(0).toString().contains("标题"));
            assertEquals(first, service.list(workspace, 0, 20, ""), "canceling caller's local selection leaves pure directory unchanged");
        }
    }
}
