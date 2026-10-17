package com.codeagent.config;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Locked raw-JSON patches. No user/network callbacks execute under this file lock. */
public class UserConfigStore {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public enum Failure { INVALID_CONFIG, IO, BUSY, CONFLICT, UNSAFE_PATH, PERMISSIONS, ATOMIC_MOVE_UNSUPPORTED }
    public static final class ConfigStoreException extends RuntimeException {
        private final Failure reason;
        public ConfigStoreException(Failure reason) { super("User configuration operation failed: " + reason); this.reason = reason; }
        public Failure reason() { return reason; }
    }
    public static final class Snapshot {
        private final Path file;
        private final ObjectNode document;
        private final String fingerprint;
        private final boolean exists;
        private Snapshot(Path file, ObjectNode document, byte[] bytes) {
            this.file = file; this.document = document.deepCopy();
            exists = bytes.length != 0;
            try { fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
        }
        public ObjectNode document() { return document.deepCopy(); }
        public String fingerprint() { return fingerprint; }
        public boolean exists() { return exists; }
        @Override public String toString() { return "ConfigSnapshot[redacted]"; }
    }
    private final Path file, root;
    public UserConfigStore() { this(CodeAgentPaths.resolve("config.json")); }
    public UserConfigStore(Path configFile) {
        file = Objects.requireNonNull(configFile).toAbsolutePath().normalize();
        root = Objects.requireNonNull(file.getParent());
    }
    public Path configFile() { return file; }

    public Snapshot readSnapshot() {
        try {
            checkPath(root); checkPath(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Snapshot(file, JSON.createObjectNode(), new byte[0]);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw failure(Failure.UNSAFE_PATH);
            byte[] bytes = Files.readAllBytes(file);
            JsonNode node;
            try { node = JSON.readTree(bytes); }
            catch (IOException e) { throw failure(Failure.INVALID_CONFIG); }
            if (!(node instanceof ObjectNode object)) throw failure(Failure.INVALID_CONFIG);
            if (object.has("providers") && !object.path("providers").isObject()) throw failure(Failure.INVALID_CONFIG);
            return new Snapshot(file, object, bytes);
        } catch (IOException | SecurityException e) { throw failure(Failure.IO); }
    }

    public Snapshot saveProvider(ProviderSettingsDraft draft) {
        Objects.requireNonNull(draft);
        String selected = draft.baseline().document.path("defaultProvider").asText("").trim().toLowerCase(Locale.ROOT);
        return saveProvider(draft, draft.provider().equals(ProviderDescriptor.normalizeId(selected)) ? selected : draft.provider());
    }

    Snapshot saveProvider(ProviderSettingsDraft draft, String configuredId) {
        Objects.requireNonNull(draft);
        requireBaseline(draft.baseline());
        return update(current -> {
            ObjectNode baseline = draft.baseline().document;
            JsonNode oldProvider = baseline.path("providers").path(draft.provider());
            JsonNode latestProvider = current.path("providers").path(draft.provider());
            if (!oldProvider.equals(latestProvider)
                    || !baseline.path("defaultProvider").equals(current.path("defaultProvider"))) throw failure(Failure.CONFLICT);
            // KEEP and unedited fields may depend on the selected alias, even with a canonical UI choice.
            if (!draft.provider().equals(configuredId)
                    && !baseline.path("providers").path(configuredId).equals(current.path("providers").path(configuredId)))
                throw failure(Failure.CONFLICT);
            if (!latestProvider.isMissingNode() && !latestProvider.isObject()) throw failure(Failure.INVALID_CONFIG);
            ObjectNode providers = current.withObject("/providers");
            ObjectNode provider = providers.withObject("/" + draft.provider());
            putEdited(provider, "model", draft.model());
            putEdited(provider, "baseUrl", draft.baseUrl());
            putEdited(provider, "loraId", draft.loraId());
            if (draft.secretUpdate().action() == SecretUpdate.Action.REPLACE) provider.put("apiKey", draft.secretUpdate().replacement());
            // An existing alias can carry an explicit alias-only JSON key. Keep that selection's fallback intact.
            if (draft.makeDefault() && !draft.provider().equals(ProviderDescriptor.normalizeId(current.path("defaultProvider").asText(null))))
                current.put("defaultProvider", draft.provider());
            return current;
        });
    }

    /** Compatibility adapter: calculate edits against the loaded typed view, preserve raw unknown fields. */
    Snapshot saveChanges(Snapshot baseline, ObjectNode previousView, ObjectNode changedView) {
        if (baseline != null) requireBaseline(baseline);
        return update(current -> {
            mergeChanges(current, baseline == null ? JSON.createObjectNode() : baseline.document,
                    previousView == null ? JSON.createObjectNode() : previousView, changedView);
            return current;
        });
    }

    private static void mergeChanges(ObjectNode target, JsonNode rawBaseline, ObjectNode oldView, ObjectNode newView) {
        Set<String> fields = new LinkedHashSet<>(); oldView.fieldNames().forEachRemaining(fields::add); newView.fieldNames().forEachRemaining(fields::add);
        for (String name : fields) {
            JsonNode before = oldView.path(name), after = newView.path(name);
            if (before.equals(after)) continue;
            if (after instanceof ObjectNode object && (before.isObject() || before.isMissingNode())) {
                JsonNode latest = target.path(name);
                if (!latest.isObject() && !latest.isMissingNode()) throw failure(Failure.CONFLICT);
                ObjectNode nested = latest.isObject() ? (ObjectNode) latest : target.putObject(name);
                mergeChanges(nested, rawBaseline.path(name), before.isObject() ? (ObjectNode) before : JSON.createObjectNode(), object);
            } else {
                if (!target.path(name).equals(rawBaseline.path(name))) throw failure(Failure.CONFLICT);
                if (after.isMissingNode()) target.remove(name); else target.set(name, after.deepCopy());
            }
        }
    }

    private Snapshot update(UnaryOperator<ObjectNode> patch) {
        Path temporary = null;
        try {
            checkPath(root);
            Files.createDirectories(root);
            checkPath(root);
            Path lockFile = root.resolve("config.lock");
            checkPath(lockFile);
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException e) { throw failure(Failure.BUSY); }
                if (lock == null) throw failure(Failure.BUSY);
                try (lock) {
                    restrictPermissions(lockFile);
                    Snapshot current = readSnapshot();
                    ObjectNode merged = patch.apply(current.document());
                    byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(merged);
                    temporary = Files.createTempFile(root, ".config-", ".tmp");
                    checkPath(temporary);
                    restrictPermissions(temporary);
                    try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining()) output.write(buffer);
                        output.force(true);
                    }
                    checkPath(file);
                    atomicReplace(temporary, file);
                    temporary = null;
                    return new Snapshot(file, merged, bytes);
                }
            }
        } catch (AtomicMoveNotSupportedException e) { throw failure(Failure.ATOMIC_MOVE_UNSUPPORTED); }
        catch (IOException | SecurityException e) { throw failure(Failure.IO); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }

    protected void atomicReplace(Path source, Path destination) throws IOException {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    protected void restrictPermissions(Path path) {
        try {
            var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
                posix.setPermissions(permissions);
                if (!posix.readAttributes().permissions().equals(permissions)) throw failure(Failure.PERMISSIONS);
                return;
            }
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw failure(Failure.PERMISSIONS);
            UserPrincipal user = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            if (!acl.getOwner().equals(user)) throw failure(Failure.PERMISSIONS);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            if (acl.getAcl().stream().anyMatch(e -> !e.principal().equals(user) || e.type() != AclEntryType.ALLOW)) throw failure(Failure.PERMISSIONS);
        } catch (IOException | UnsupportedOperationException | SecurityException e) { throw failure(Failure.PERMISSIONS); }
    }

    private void requireBaseline(Snapshot snapshot) {
        if (!file.equals(snapshot.file)) throw failure(Failure.CONFLICT);
    }
    private static void putEdited(ObjectNode node, String name, String value) { if (value != null) node.put(name, value); }
    private static ConfigStoreException failure(Failure reason) { return new ConfigStoreException(reason); }
    private static void checkPath(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part) || (Files.exists(part, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther())) throw failure(Failure.UNSAFE_PATH);
        }
    }
}
