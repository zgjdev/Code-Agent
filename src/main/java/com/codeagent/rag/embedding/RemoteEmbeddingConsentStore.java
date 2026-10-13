package com.codeagent.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class RemoteEmbeddingConsentStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path file;
    private final Object lock = new Object();

    public RemoteEmbeddingConsentStore() {
        this(Path.of(System.getProperty("user.home"), ".codeagent", "rag", "remote-consents.json"));
    }

    public RemoteEmbeddingConsentStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Path file() {
        return file;
    }

    public Optional<RemoteEmbeddingConsent> find(RemoteEmbeddingConsentRequest request) throws IOException {
        synchronized (lock) {
            return readAll().stream().filter(consent -> matches(consent, request)).findFirst();
        }
    }

    public void grant(RemoteEmbeddingConsent consent) throws IOException {
        synchronized (lock) {
            List<RemoteEmbeddingConsent> consents = new ArrayList<>(readAll());
            consents.removeIf(existing -> sameScope(existing, consent));
            consents.add(consent);
            writeAll(consents);
        }
    }

    public int revokeProject(String projectFingerprint) throws IOException {
        synchronized (lock) {
            List<RemoteEmbeddingConsent> consents = new ArrayList<>(readAll());
            int before = consents.size();
            consents.removeIf(consent -> consent.projectFingerprint().equals(projectFingerprint));
            int removed = before - consents.size();
            if (removed > 0) writeAll(consents);
            return removed;
        }
    }

    private List<RemoteEmbeddingConsent> readAll() throws IOException {
        if (Files.notExists(file)) return List.of();
        JsonNode root = MAPPER.readTree(file.toFile());
        if (root == null || !root.isArray()) {
            throw new IOException("Remote embedding consent file must contain a JSON array");
        }
        List<RemoteEmbeddingConsent> result = new ArrayList<>();
        for (JsonNode node : root) {
            result.add(new RemoteEmbeddingConsent(
                    requiredText(node, "projectFingerprint"),
                    requiredText(node, "providerId"),
                    requiredText(node, "modelId"),
                    requiredText(node, "endpointFingerprint"),
                    node.path("consentPolicyVersion").asInt(),
                    Instant.parse(requiredText(node, "grantedAt"))));
        }
        return result;
    }

    private void writeAll(List<RemoteEmbeddingConsent> consents) throws IOException {
        Path parent = file.getParent();
        if (parent == null) throw new IOException("Consent file must have a parent directory");
        Files.createDirectories(parent);
        setPosixPermissionsIfSupported(parent, DIRECTORY_PERMISSIONS);

        ArrayNode root = MAPPER.createArrayNode();
        consents.stream().sorted(Comparator
                .comparing(RemoteEmbeddingConsent::projectFingerprint)
                .thenComparing(RemoteEmbeddingConsent::providerId)
                .thenComparing(RemoteEmbeddingConsent::modelId)
                .thenComparing(RemoteEmbeddingConsent::endpointFingerprint)
                .thenComparingInt(RemoteEmbeddingConsent::consentPolicyVersion))
                .forEach(consent -> root.add(toJson(consent)));

        Path temp = Files.createTempFile(parent, "remote-consents-", ".tmp");
        try {
            Files.writeString(temp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            setPosixPermissionsIfSupported(temp, FILE_PERMISSIONS);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            setPosixPermissionsIfSupported(file, FILE_PERMISSIONS);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static ObjectNode toJson(RemoteEmbeddingConsent consent) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("projectFingerprint", consent.projectFingerprint());
        node.put("providerId", consent.providerId());
        node.put("modelId", consent.modelId());
        node.put("endpointFingerprint", consent.endpointFingerprint());
        node.put("consentPolicyVersion", consent.consentPolicyVersion());
        node.put("grantedAt", consent.grantedAt().toString());
        return node;
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IOException("Missing consent field: " + field);
        return value;
    }

    private static boolean matches(RemoteEmbeddingConsent consent, RemoteEmbeddingConsentRequest request) {
        return consent.projectFingerprint().equals(request.projectFingerprint())
                && consent.providerId().equals(request.providerId())
                && consent.modelId().equals(request.modelId())
                && consent.endpointFingerprint().equals(request.endpointFingerprint())
                && consent.consentPolicyVersion() == request.consentPolicyVersion();
    }

    private static boolean sameScope(RemoteEmbeddingConsent left, RemoteEmbeddingConsent right) {
        return left.projectFingerprint().equals(right.projectFingerprint())
                && left.providerId().equals(right.providerId())
                && left.modelId().equals(right.modelId())
                && left.endpointFingerprint().equals(right.endpointFingerprint())
                && left.consentPolicyVersion() == right.consentPolicyVersion();
    }

    private static void setPosixPermissionsIfSupported(
            Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX file systems use platform defaults.
        }
    }
}
