package com.codeagent.history;

import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Idempotently imports a v1 raw ConversationLedger without deleting the source file. */
public final class LegacySessionMigrator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SessionStore store;
    private final Path workspace;

    public LegacySessionMigrator(SessionStore store, Path workspace) {
        this.store = Objects.requireNonNull(store, "store");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    public SessionSummary migrate(Path rawFile) throws IOException {
        Objects.requireNonNull(rawFile, "rawFile");
        byte[] source = Files.readAllBytes(rawFile);
        String sha256 = sha256(source);
        var existing = store.findLegacySource(workspace, sha256);
        if (existing.isPresent()) return existing.get();

        List<ConversationLedger.Entry> entries;
        try (var lines = Files.lines(rawFile)) {
            try {
                entries = lines.filter(line -> !line.isBlank()).map(line -> {
                    try { return JSON.readValue(line, ConversationLedger.Entry.class); }
                    catch (IOException e) { throw new LegacyReadException(e); }
                }).toList();
            } catch (LegacyReadException e) {
                throw e.cause;
            }
        }
        boolean unsafe = entries.stream().anyMatch(entry -> "compaction".equals(entry.event()));
        SessionStore.SessionCreateRequest request = new SessionStore.SessionCreateRequest(
                workspace, "legacy", "unknown", null, "react", "legacy-migrator");
        try (SessionStore.SessionHandle handle = store.createLegacy(request, sha256, unsafe)) {
            Long systemSequence = null;
            for (ConversationLedger.Entry entry : entries) {
                String event = entry.event();
                LlmClient.Message message = entry.message();
                if ("history_clear".equals(event)) {
                    handle.append(draft(entry, SessionEvent.Types.SURFACE_CLEAR,
                            SessionEvent.SurfaceOperation.clear(), legacyPayload(entry)));
                    systemSequence = null;
                } else if ("system".equals(event) && message != null) {
                    SessionEvent persisted = handle.append(messageDraft(entry, SessionEvent.Types.SYSTEM_MESSAGE,
                            message, systemSequence == null ? SessionEvent.SurfaceOperation.append()
                                    : SessionEvent.SurfaceOperation.replace(systemSequence, systemSequence)));
                    systemSequence = persisted.sequence();
                } else if ("user".equals(event) && message != null) {
                    handle.append(messageDraft(entry, SessionEvent.Types.USER_MESSAGE, message,
                            SessionEvent.SurfaceOperation.append()));
                } else if ("assistant".equals(event) && message != null) {
                    handle.append(messageDraft(entry, SessionEvent.Types.ASSISTANT_MESSAGE, message,
                            SessionEvent.SurfaceOperation.append()));
                } else if ("tool_call".equals(event) && message != null) {
                    handle.append(messageDraft(entry, SessionEvent.Types.ASSISTANT_MESSAGE, message,
                            SessionEvent.SurfaceOperation.append()));
                    if (message.toolCalls() != null) for (LlmClient.ToolCall call : message.toolCalls()) {
                        ObjectNode payload = legacyPayload(entry).put("invocationId", call.id())
                                .put("name", call.function().name()).put("arguments", call.function().arguments());
                        handle.append(draft(entry, SessionEvent.Types.TOOL_CALL,
                                SessionEvent.SurfaceOperation.none(), payload));
                    }
                } else if ("tool_result".equals(event) && message != null) {
                    ObjectNode payload = legacyPayload(entry).put("invocationId",
                            message.toolCallId() == null ? "legacy-" + entry.sequence() : message.toolCallId());
                    payload.set("message", JSON.valueToTree(message));
                    handle.append(draft(entry, SessionEvent.Types.TOOL_RESULT,
                            SessionEvent.SurfaceOperation.append(), payload));
                } else {
                    handle.append(draft(entry, SessionEvent.Types.LEGACY_EVENT,
                            SessionEvent.SurfaceOperation.none(), legacyPayload(entry)));
                }
            }
            handle.markClosed("legacy-migration");
            return SessionSummary.from(handle.manifest());
        }
    }

    private SessionEventDraft messageDraft(ConversationLedger.Entry entry, String type,
                                           LlmClient.Message message,
                                           SessionEvent.SurfaceOperation surface) {
        ObjectNode payload = legacyPayload(entry);
        payload.set("message", JSON.valueToTree(message));
        return draft(entry, type, surface, payload);
    }

    private SessionEventDraft draft(ConversationLedger.Entry entry, String type,
                                    SessionEvent.SurfaceOperation surface, ObjectNode payload) {
        return new SessionEventDraft(type, entry.mode(), entry.actor(), "legacy:" + entry.source(),
                SessionEvent.Types.LEGACY_EVENT.equals(type), surface, payload);
    }

    private ObjectNode legacyPayload(ConversationLedger.Entry entry) {
        ObjectNode payload = JSON.createObjectNode().put("legacySequence", entry.sequence())
                .put("legacyEvent", entry.event());
        payload.set("legacyEntry", JSON.valueToTree(entry));
        return payload;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    private static final class LegacyReadException extends RuntimeException {
        private final IOException cause;
        private LegacyReadException(IOException cause) { this.cause = cause; }
    }
}
