package com.codeagent.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionDurabilityBoundaryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test void invalidAppendDoesNotChangeDurableOrPublishedPrefix() throws Exception {
        try (var store = SessionStore.open(directory); var session = store.create(request())) {
            Path log = session.sessionDirectory().resolve("events.jsonl");
            byte[] before = Files.readAllBytes(log);
            var projection = session.projection();
            var events = session.readAll();
            assertThrows(IllegalArgumentException.class, () -> session.append(new SessionEventDraft(
                    "unknown/required", "react", "test", "test", false,
                    SessionEvent.SurfaceOperation.none(), JSON.createObjectNode())));
            assertArrayEquals(before, Files.readAllBytes(log));
            assertEquals(events, session.readAll());
            assertEquals(projection, session.projection());
        }
    }

    @Test void validJsonWithoutNewlineIsNotCommitted() throws Exception {
        String id;
        Path log;
        try (var store = SessionStore.open(directory); var session = store.create(request())) {
            id = session.sessionId();
            log = session.sessionDirectory().resolve("events.jsonl");
        }
        var tail = new SessionEvent(2, id, 1, 2, SessionEvent.Types.SESSION_END,
                "react", "test", "test", false, SessionEvent.SurfaceOperation.none(), JSON.createObjectNode());
        Files.write(log, JSON.writeValueAsBytes(tail), StandardOpenOption.APPEND);
        try (var store = SessionStore.open(directory)) {
            assertEquals(0, store.readProjection(id).lastAppliedSequence());
            assertEquals(0, store.readParentSnapshot(id, directory, -1).lastSequence());
            try (var session = store.resumeWritable(id, directory)) {
                assertEquals(0, session.projection().lastAppliedSequence());
                assertTrue(Files.readString(log).endsWith("\n"));
            }
        }
    }

    @Test void publicCheckpointWriterRejectsUnfinishedCompaction() throws Exception {
        try (var store = SessionStore.open(directory); var session = store.create(request())) {
            session.append(new SessionEventDraft(SessionEvent.Types.COMPACTION_START,
                    "react", "test", "test", false, SessionEvent.SurfaceOperation.none(),
                    JSON.createObjectNode().put("compactionId", "pending")));
            new SessionCheckpointStore(session.sessionDirectory()).write(
                    session.sessionId(), session.readAll(), session.projection());
            try (var files = Files.list(session.sessionDirectory().resolve("checkpoints"))) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test void malformedUtf8InsideCommittedJsonIsRejectedByAllReaders() throws Exception {
        String id; Path log;
        try (var store = SessionStore.open(directory); var session = store.create(request())) {
            id = session.sessionId(); log = session.sessionDirectory().resolve("events.jsonl");
            session.append(new SessionEventDraft(SessionEvent.Types.LEGACY_EVENT, "react", "test", "test", true,
                    SessionEvent.SurfaceOperation.none(), JSON.createObjectNode().put("marker", "utf8-marker")));
        }
        byte[] bytes = Files.readAllBytes(log);
        byte[] marker = "utf8-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int offset = -1;
        for (int index = 0; index <= bytes.length - marker.length; index++) {
            if (java.util.Arrays.equals(bytes, index, index + marker.length, marker, 0, marker.length)) { offset = index; break; }
        }
        assertTrue(offset >= 0);
        bytes[offset] = (byte) 0xc3; // Invalid two-byte prefix followed by ASCII, still inside valid JSON quotes.
        Files.write(log, bytes);
        try (var store = SessionStore.open(directory)) {
            assertThrows(SessionStore.CorruptSessionLogException.class, () -> store.readProjection(id));
            assertThrows(SessionStore.CorruptSessionLogException.class, () -> store.readParentSnapshot(id, directory, -1));
            assertThrows(SessionStore.CorruptSessionLogException.class, () -> store.resumeWritable(id, directory));
        }
    }

    @Test void checkpointRoundTripsExecutionEnvelopes() throws Exception {
        try (var store = SessionStore.open(directory); var session = store.create(request())) {
            session.append(new SessionEventDraft(SessionEvent.Types.EXECUTION_START,
                    "react", "test", "test", false, SessionEvent.SurfaceOperation.none(),
                    JSON.createObjectNode().put("executionId", "execution").put("ordinal", 1)));
            var checkpoints = new SessionCheckpointStore(session.sessionDirectory());
            checkpoints.write(session.sessionId(), session.readAll(), session.projection());
            var loaded = checkpoints.loadLatest(session.manifest(), session.readAll());
            assertTrue(loaded.checkpoint().isPresent());
            assertEquals(session.projection(), loaded.projection());
        }
    }

    private SessionStore.SessionCreateRequest request() {
        return new SessionStore.SessionCreateRequest(directory, "test", "test", null, "react", "test");
    }
}
