package com.codeagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class UserConfigStoreTest {
    @TempDir Path directory;
    private ProviderSettingsDraft draft(UserConfigStore.Snapshot snapshot, String provider, boolean makeDefault) {
        return new ProviderSettingsDraft(snapshot, provider, "edited-model", null, null, SecretUpdate.keep(), makeDefault);
    }

    @Test void patchesOnlyEditedFieldsAndMergesUnrelatedUpdates() throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, "{\"future\":1,\"providers\":{\"glm\":{\"apiKey\":\"fixture-secret\",\"future\":true}}}");
        var store = new UserConfigStore(file);
        var draft = draft(store.readSnapshot(), "glm", true);
        Files.writeString(file, "{\"future\":2,\"providers\":{\"glm\":{\"apiKey\":\"fixture-secret\",\"future\":true},\"step\":{\"model\":\"concurrent\"}}}");
        var saved = store.saveProvider(draft).document();
        assertEquals(2, saved.path("future").asInt());
        assertTrue(saved.at("/providers/glm/future").asBoolean());
        assertEquals("concurrent", saved.at("/providers/step/model").asText());
        assertEquals("edited-model", saved.at("/providers/glm/model").asText());
        assertTrue("fixture-secret".equals(saved.at("/providers/glm/apiKey").asText()));
    }

    @Test void rejectsTargetAndDefaultConflictsWithoutChangingFile() throws Exception {
        Path file = directory.resolve("config.json");
        var store = new UserConfigStore(file);
        var draft = draft(store.readSnapshot(), "glm", true);
        Files.writeString(file, "{\"defaultProvider\":\"step\"}");
        String before = Files.readString(file);
        assertEquals(UserConfigStore.Failure.CONFLICT,
                assertThrows(UserConfigStore.ConfigStoreException.class, () -> store.saveProvider(draft)).reason());
        assertEquals(before, Files.readString(file));
        var next = draft(store.readSnapshot(), "glm", false);
        Files.writeString(file, "{\"defaultProvider\":\"step\",\"providers\":{\"glm\":{\"model\":\"other\"}}}");
        assertEquals(UserConfigStore.Failure.CONFLICT,
                assertThrows(UserConfigStore.ConfigStoreException.class, () -> store.saveProvider(next)).reason());
    }

    @Test void reportsBusyAndDoesNotWaitForLock() throws Exception {
        var store = new UserConfigStore(directory.resolve("config.json"));
        var draft = draft(store.readSnapshot(), "glm", false);
        try (var channel = FileChannel.open(directory.resolve("config.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertEquals(UserConfigStore.Failure.BUSY,
                    assertThrows(UserConfigStore.ConfigStoreException.class, () -> store.saveProvider(draft)).reason());
        }
        assertFalse(Files.exists(directory.resolve("config.json")));
    }

    @Test void aliasDependencyChangedAfterDraftIsAConflict() throws Exception {
        Path file = directory.resolve("config.json");
        var store = new UserConfigStore(file);
        for (String setting : new String[]{"apiKey", "model", "baseUrl", "loraId"}) {
            Files.writeString(file, "{\"defaultProvider\":\"moonshot\",\"providers\":{\"moonshot\":{\"" + setting + "\":\"before\"}}}");
            try (var draft = draft(store.readSnapshot(), "kimi", true)) {
                String concurrent = "{\"defaultProvider\":\"moonshot\",\"providers\":{\"moonshot\":{\"" + setting + "\":\"after\"}}}";
                Files.writeString(file, concurrent);
                assertEquals(UserConfigStore.Failure.CONFLICT, assertThrows(UserConfigStore.ConfigStoreException.class,
                        () -> store.saveProvider(draft)).reason());
                assertEquals(concurrent, Files.readString(file));
            }
        }
    }

    @Test void unchangedAliasStillAllowsUnrelatedProviderAndUnknownFieldMerge() throws Exception {
        Path file = directory.resolve("config.json");
        String alias = "\"moonshot\":{\"apiKey\":\"fixture-alias-key\",\"model\":\"alias-model\",\"future\":true}";
        Files.writeString(file, "{\"defaultProvider\":\"moonshot\",\"providers\":{" + alias + "}}");
        var store = new UserConfigStore(file);
        try (var draft = draft(store.readSnapshot(), "kimi", true)) {
            Files.writeString(file, "{\"defaultProvider\":\"moonshot\",\"future\":42,\"providers\":{" + alias
                    + ",\"glm\":{\"model\":\"concurrent-model\"}}}");
            var saved = store.saveProvider(draft).document();
            assertEquals("moonshot", saved.path("defaultProvider").asText());
            assertEquals("fixture-alias-key", saved.at("/providers/moonshot/apiKey").asText());
            assertTrue(saved.at("/providers/moonshot/future").asBoolean());
            assertFalse(saved.at("/providers/kimi").has("apiKey"));
            assertEquals("edited-model", saved.at("/providers/kimi/model").asText());
            assertEquals("concurrent-model", saved.at("/providers/glm/model").asText());
            assertEquals(42, saved.path("future").asInt());
        }
    }

    @Test void refusesMalformedOrNonObjectJsonWithoutLeakingContent() throws Exception {
        Path file = directory.resolve("config.json");
        for (String invalid : new String[]{"{private-fixture", "[]", "null", "{} {}"}) {
            Files.writeString(file, invalid);
            var error = assertThrows(UserConfigStore.ConfigStoreException.class, () -> new UserConfigStore(file).readSnapshot());
            assertEquals(UserConfigStore.Failure.INVALID_CONFIG, error.reason());
            assertFalse(error.toString().contains("private-fixture"));
            assertNull(error.getCause());
            assertEquals(invalid, Files.readString(file));
        }
    }

    @Test void replacesSecretAndKeepsItOutOfDiagnosticObjects() throws Exception {
        var store = new UserConfigStore(directory.resolve("config.json"));
        try (var secret = SecretUpdate.replace("private-fixture".toCharArray());
             var draft = new ProviderSettingsDraft(store.readSnapshot(), "glm", null, null, null, secret, true)) {
            assertFalse(secret.toString().contains("private-fixture"));
            assertFalse(draft.toString().contains("private-fixture"));
            assertTrue("private-fixture".equals(store.saveProvider(draft).document().at("/providers/glm/apiKey").asText()));
        }
        try (var children = Files.list(directory)) {
            assertFalse(children.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
        var posix = Files.getFileAttributeView(directory.resolve("config.json"), java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions());
        var acl = Files.getFileAttributeView(directory.resolve("config.json"), java.nio.file.attribute.AclFileAttributeView.class);
        if (acl != null) {
            var owner = acl.getOwner();
            assertTrue(acl.getAcl().stream().allMatch(e -> e.principal().equals(owner)));
        }
    }

    @Test void refusesSymlinkTargetsWhereSupported() throws Exception {
        Path external = directory.resolve("outside.json");
        Files.writeString(external, "{}");
        Path root = Files.createDirectory(directory.resolve("root"));
        try { Files.createSymbolicLink(root.resolve("config.json"), external); }
        catch (UnsupportedOperationException | java.io.IOException e) { org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink unavailable"); }
        assertThrows(UserConfigStore.ConfigStoreException.class, () -> new UserConfigStore(root.resolve("config.json")).readSnapshot());
        assertEquals("{}", Files.readString(external));
    }

    @Test void failuresKeepOriginalBytesAndCleanOnlyOwnedTemporaryFiles() throws Exception {
        Path file = directory.resolve("config.json"), unrelated = directory.resolve("unrelated.tmp");
        Files.writeString(file, "{\"providers\":{\"glm\":{\"model\":\"old\"}}}");
        Files.writeString(unrelated, "unrelated");
        String original = Files.readString(file);
        for (var reason : new UserConfigStore.Failure[]{UserConfigStore.Failure.ATOMIC_MOVE_UNSUPPORTED, UserConfigStore.Failure.IO, UserConfigStore.Failure.PERMISSIONS}) {
            var store = new UserConfigStore(file) {
                @Override protected void atomicReplace(Path from, Path to) throws java.io.IOException {
                    if (reason == UserConfigStore.Failure.ATOMIC_MOVE_UNSUPPORTED) throw new AtomicMoveNotSupportedException("redacted", "redacted", "private-fixture");
                    throw new java.io.IOException("private-fixture");
                }
                @Override protected void restrictPermissions(Path path) {
                    if (reason == UserConfigStore.Failure.PERMISSIONS) throw new UserConfigStore.ConfigStoreException(reason);
                    super.restrictPermissions(path);
                }
            };
            try (var draft = draft(store.readSnapshot(), "glm", false)) {
                var error = assertThrows(UserConfigStore.ConfigStoreException.class, () -> store.saveProvider(draft));
                assertEquals(reason, error.reason());
                assertFalse(error.toString().contains("private-fixture"));
            }
            assertEquals(original, Files.readString(file));
            assertEquals("unrelated", Files.readString(unrelated));
            try (var paths = Files.list(directory)) { assertEquals(3, paths.count()); }
        }
    }

    @Test void snapshotCannotBeMutatedOrAppliedToAnotherRoot() throws Exception {
        var store = new UserConfigStore(directory.resolve("config.json"));
        var snapshot = store.readSnapshot();
        snapshot.document().put("defaultProvider", "untrusted");
        assertFalse(snapshot.document().has("defaultProvider"));
        try (var draft = draft(snapshot, "glm", true)) {
            assertThrows(UserConfigStore.ConfigStoreException.class,
                    () -> new UserConfigStore(directory.resolve("other/config.json")).saveProvider(draft));
            assertFalse(Files.exists(directory.resolve("other")));
        }
    }

    @Test void refusesWindowsJunctionRoot() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path link = directory.resolve("junction");
        var process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(process.waitFor() == 0, "junction unavailable");
        try {
            assertEquals(UserConfigStore.Failure.UNSAFE_PATH, assertThrows(UserConfigStore.ConfigStoreException.class,
                    () -> new UserConfigStore(link.resolve("config.json")).readSnapshot()).reason());
            assertFalse(Files.exists(outside.resolve("config.json")));
        } finally { Files.deleteIfExists(link); }
    }
}
