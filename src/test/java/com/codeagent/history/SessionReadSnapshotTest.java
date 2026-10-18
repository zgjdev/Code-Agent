package com.codeagent.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class SessionReadSnapshotTest {
    @TempDir Path temp;

    @Test
    void readsValidatedPrefixWhileWriterOwnsLockWithoutRepairingTail() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history"));
             var handle = store.create(new SessionStore.SessionCreateRequest(
                     workspace, "test", "model", null, "react", "agent"))) {
            Path file = handle.sessionDirectory().resolve("events.jsonl");
            Files.writeString(file, "{incomplete", StandardOpenOption.APPEND);
            byte[] before = Files.readAllBytes(file);
            var snapshot = store.readParentSnapshot(handle.sessionId(), workspace, -1);
            assertEquals(0, snapshot.lastSequence());
            assertEquals(handle.manifest(), snapshot.manifest());
            assertEquals(1, snapshot.events().size());
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(0, Files.list(handle.sessionDirectory())
                    .filter(p -> p.getFileName().toString().endsWith(".part")).count());
            assertThrows(SessionStore.SessionLockedException.class,
                    () -> store.resumeWritable(handle.sessionId(), workspace));
        }
    }

    @Test
    void refusesWorkspaceParentIdentityTraversalAndUnavailableWatermark() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path other = Files.createDirectory(temp.resolve("other"));
        try (SessionStore store = SessionStore.open(temp.resolve("history"));
             var handle = store.create(new SessionStore.SessionCreateRequest(
                     workspace, "test", "model", null, "react", "agent"));
             var child = handle.createChild("plan", "task:one")) {
            assertThrows(Exception.class, () -> store.readParentSnapshot("..", workspace, -1));
            assertThrows(SessionStore.WorkspaceMismatchException.class,
                    () -> store.readParentSnapshot(handle.sessionId(), other, -1));
            assertThrows(java.io.IOException.class,
                    () -> store.readParentSnapshot(child.sessionId(), workspace, -1));
            assertEquals(child.sessionId(), store.readSnapshot(
                    child.sessionId(), workspace, handle.sessionId(), -1).manifest().sessionId());
            assertThrows(java.io.IOException.class,
                    () -> store.readParentSnapshot(handle.sessionId(), workspace, 99));
            var manifest = new ObjectMapper().valueToTree(handle.manifest());
            ((com.fasterxml.jackson.databind.node.ObjectNode) manifest).put("sessionId", "wrong");
            Files.writeString(handle.sessionDirectory().resolve("manifest.json"), manifest.toString());
            assertThrows(java.io.IOException.class,
                    () -> store.readParentSnapshot(handle.sessionId(), workspace, -1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sequence", "sessionId", "schema", "workspace", "parent", "futureIgnorable"})
    void refusesCorruptOrUnrecognizedPrefixWithoutPrivateParserCause(String corruption) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history"));
             var handle = SessionTranscriptReaderTest.create(store, workspace)) {
            Path events = handle.sessionDirectory().resolve("events.jsonl");
            var start = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper()
                    .readTree(Files.readString(events));
            switch (corruption) {
                case "sequence" -> start.put("sequence", 9);
                case "sessionId" -> start.put("sessionId", "private-body");
                case "schema" -> start.put("schemaVersion", 0);
                case "futureIgnorable" -> { start.put("schemaVersion", 999); start.put("ignorable", true); }
                case "workspace" -> ((com.fasterxml.jackson.databind.node.ObjectNode) start.get("payload")).put("workspace", "private-body");
                case "parent" -> ((com.fasterxml.jackson.databind.node.ObjectNode) start.get("payload")).put("parentSessionId", "private-body");
            }
            Files.writeString(events, start + "\n");
            byte[] before = Files.readAllBytes(events);
            var failure = assertThrows(java.io.IOException.class,
                    () -> store.readParentSnapshot(handle.sessionId(), workspace, -1));
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("private-body"));
            assertArrayEquals(before, Files.readAllBytes(events));
        }
    }

    @Test
    void fixedPrefixSurvivesLaterCorruptUtf8AndManifestAheadOrBehindNeverInflatesWatermark() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        try (SessionStore store = SessionStore.open(temp.resolve("history"));
             var handle = SessionTranscriptReaderTest.create(store, workspace)) {
            var snapshot = store.readParentSnapshot(handle.sessionId(), workspace, -1);
            var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().valueToTree(handle.manifest());
            manifest.put("lastEventSequence", 999);
            Files.writeString(handle.sessionDirectory().resolve("manifest.json"), manifest.toString());
            assertEquals(0, store.readParentSnapshot(handle.sessionId(), workspace, -1).lastSequence());
            var raw = snapshot.events();
            ((com.fasterxml.jackson.databind.node.ObjectNode) raw.get(0).payload()).put("workspace", "mutated");
            assertEquals(workspace.toRealPath().toString(), snapshot.events().get(0).payload().path("workspace").asText());
            Files.write(handle.sessionDirectory().resolve("events.jsonl"), new byte[]{(byte) 0xff, '\n'}, StandardOpenOption.APPEND);
            assertEquals(0, store.readParentSnapshot(handle.sessionId(), workspace, snapshot.lastSequence()).lastSequence());
            assertThrows(java.io.IOException.class, () -> store.readParentSnapshot(handle.sessionId(), workspace, -1));
        }
    }

    @Test
    void refusesRedirectedSessionDirectoryAndAncestorWithoutTouchingTarget() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path history = temp.resolve("history");
        try (SessionStore store = SessionStore.open(history);
             var handle = SessionTranscriptReaderTest.create(store, workspace)) {
            Path sessions = history.resolve("sessions");
            Path redirect = sessions.resolve("redirect");
            createDirectoryLink(redirect, handle.sessionDirectory());
            byte[] before = Files.readAllBytes(handle.sessionDirectory().resolve("events.jsonl"));
            try {
                assertThrows(java.io.IOException.class, () -> store.readParentSnapshot("redirect", workspace, -1));
                assertArrayEquals(before, Files.readAllBytes(handle.sessionDirectory().resolve("events.jsonl")));
            } finally { Files.delete(redirect); }
            Path ancestor = temp.resolve("linked-history");
            createDirectoryLink(ancestor, history);
            try (SessionStore linked = SessionStore.open(ancestor)) {
                assertThrows(java.io.IOException.class,
                        () -> linked.readParentSnapshot(handle.sessionId(), workspace, -1));
                assertThrows(java.io.IOException.class, linked::readSessionIds);
            } finally { Files.delete(ancestor); }
        }
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor(), "isolated junction fixture must be available");
        } else {
            Files.createSymbolicLink(link, target);
        }
    }
}
