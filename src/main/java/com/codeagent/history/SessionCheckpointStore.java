package com.codeagent.history;

import com.codeagent.context.MeasuredUsage;
import com.codeagent.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Validated acceleration snapshots. The append-only event log remains authoritative. */
public final class SessionCheckpointStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path directory;
    private final SessionReplayer replayer = new SessionReplayer();

    public SessionCheckpointStore(Path sessionDirectory) throws IOException {
        directory = sessionDirectory.resolve("checkpoints");
        Files.createDirectories(directory);
    }

    public void write(String sessionId, List<SessionEvent> prefix,
                      SessionProjection projection) throws IOException {
        if (projection.lastAppliedSequence() < 0 || !projection.incompleteRequestIds().isEmpty()) return;
        ObjectNode root = JSON.createObjectNode()
                .put("schemaVersion", SessionEvent.CURRENT_SCHEMA_VERSION)
                .put("sessionId", sessionId)
                .put("lastAppliedSequence", projection.lastAppliedSequence())
                .put("eventPrefixSha256", prefixHash(prefix, projection.lastAppliedSequence()))
                .put("createdAt", System.currentTimeMillis());
        root.set("projection", encodeProjection(projection));
        Path target = directory.resolve("checkpoint-" + projection.lastAppliedSequence() + ".json");
        Path temporary = directory.resolve(target.getFileName() + ".tmp");
        byte[] bytes = JSON.writeValueAsBytes(root);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public LoadResult loadLatest(SessionManifest manifest, List<SessionEvent> events) {
        try {
            List<Path> candidates;
            try (var files = Files.list(directory)) {
                candidates = files.filter(path -> path.getFileName().toString().startsWith("checkpoint-"))
                        .sorted(Comparator.comparingLong(SessionCheckpointStore::sequenceOf).reversed()).toList();
            }
            for (Path path : candidates) {
                try {
                    JsonNode root = JSON.readTree(path.toFile());
                    long sequence = root.path("lastAppliedSequence").asLong(-1);
                    if (root.path("schemaVersion").asInt() != SessionEvent.CURRENT_SCHEMA_VERSION
                            || !manifest.sessionId().equals(root.path("sessionId").asText())
                            || sequence < 0 || sequence >= events.size()
                            || !root.path("eventPrefixSha256").asText()
                                    .equals(prefixHash(events, sequence))) continue;
                    JsonNode projectionNode = root.path("projection");
                    if (projectionNode.path("conversationProjectionVersion").asInt(0) != 1) {
                        continue;
                    }
                    SessionProjection checkpoint = decodeProjection(projectionNode);
                    if (checkpoint.lastAppliedSequence() != sequence
                            || !checkpoint.incompleteRequestIds().isEmpty()) continue;
                    SessionProjection projection = replayer.replayFrom(manifest, checkpoint,
                            events.subList((int) sequence + 1, events.size()));
                    return new LoadResult(Optional.of(new SessionCheckpoint(sequence, path)), projection);
                } catch (Exception ignored) {
                    // A checkpoint is disposable; try an older one, then full replay.
                }
            }
        } catch (IOException ignored) {
            // Full replay is the recovery path for unreadable checkpoint storage.
        }
        return new LoadResult(Optional.empty(), replayer.replay(manifest, events));
    }

    private static ObjectNode encodeProjection(SessionProjection value) {
        ObjectNode node = JSON.createObjectNode()
                .put("lastAppliedSequence", value.lastAppliedSequence())
                .put("historyVersion", value.historyVersion())
                .put("compactionGeneration", value.compactionGeneration())
                .put("cleanlyClosed", value.cleanlyClosed())
                .put("conversationProjectionVersion", 1);
        ArrayNode surface = node.putArray("activeSurface");
        value.activeSurface().forEach(item -> {
            ObjectNode entry = surface.addObject().put("sequence", item.sequence());
            entry.set("message", JSON.valueToTree(item.message()));
        });
        ArrayNode conversation = node.putArray("topLevelConversation");
        value.topLevelConversation().forEach(item -> {
            ObjectNode entry = conversation.addObject()
                    .put("sequence", item.sequence())
                    .put("kind", item.kind().name());
            if (item.turnId() != null) entry.put("turnId", item.turnId());
            if (item.planId() != null) entry.put("planId", item.planId());
            if (item.mode() != null) entry.put("mode", item.mode());
            entry.set("message", JSON.valueToTree(item.message()));
        });
        ArrayNode openTurns = node.putArray("openTurns");
        value.openTurns().values().forEach(turn -> {
            ObjectNode entry = openTurns.addObject()
                    .put("turnId", turn.turnId())
                    .put("rootPlanId", turn.rootPlanId())
                    .put("activePlanId", turn.activePlanId());
            ArrayNode planIds = entry.putArray("planIds");
            turn.planIds().forEach(planIds::add);
        });
        ArrayNode requests = node.putArray("incompleteRequestIds");
        value.incompleteRequestIds().forEach(requests::add);
        ArrayNode tools = node.putArray("pendingTools");
        value.pendingTools().values().forEach(tool -> tools.addObject()
                .put("invocationId", tool.invocationId()).put("name", tool.name())
                .put("arguments", tool.arguments()));
        ArrayNode warnings = node.putArray("warnings");
        value.warnings().forEach(warnings::add);
        if (value.lastCompletedUsage() != null) {
            var fact = value.lastCompletedUsage();
            var usage = fact.usage();
            node.putObject("lastCompletedUsage").put("requestId", fact.requestId())
                    .put("provider", fact.provider()).put("model", fact.model())
                    .put("inputTokens", usage.inputTokens()).put("outputTokens", usage.outputTokens())
                    .put("cachedInputTokens", usage.cachedInputTokens())
                    .put("inputScope", usage.inputScope().name()).put("includesTools", usage.includesTools())
                    .put("includesSystem", usage.includesSystem()).put("trusted", usage.trusted())
                    .put("measuredAt", usage.measuredAt().toEpochMilli());
        }
        return node;
    }

    private static SessionProjection decodeProjection(JsonNode node) throws IOException {
        List<SessionProjection.SurfaceNode> surface = new ArrayList<>();
        for (JsonNode item : node.path("activeSurface")) surface.add(new SessionProjection.SurfaceNode(
                item.path("sequence").asLong(), JSON.treeToValue(item.path("message"), LlmClient.Message.class)));
        List<SessionProjection.ConversationNode> conversation = new ArrayList<>();
        for (JsonNode item : node.path("topLevelConversation")) {
            conversation.add(new SessionProjection.ConversationNode(
                    item.path("sequence").asLong(),
                    item.path("turnId").isMissingNode() || item.path("turnId").isNull() ? null : item.path("turnId").asText(),
                    item.path("planId").isMissingNode() || item.path("planId").isNull() ? null : item.path("planId").asText(),
                    item.path("mode").isMissingNode() || item.path("mode").isNull() ? null : item.path("mode").asText(),
                    JSON.treeToValue(item.path("message"), LlmClient.Message.class),
                    SessionProjection.ConversationKind.valueOf(item.path("kind").asText())));
        }
        Map<String, SessionProjection.OpenTurn> openTurns = new LinkedHashMap<>();
        for (JsonNode item : node.path("openTurns")) {
            List<String> planIds = new ArrayList<>();
            item.path("planIds").forEach(planId -> planIds.add(planId.asText()));
            SessionProjection.OpenTurn turn = new SessionProjection.OpenTurn(
                    item.path("turnId").asText(),
                    item.path("rootPlanId").asText(),
                    item.path("activePlanId").asText(),
                    planIds);
            openTurns.put(turn.turnId(), turn);
        }
        var requests = new LinkedHashSet<String>();
        node.path("incompleteRequestIds").forEach(item -> requests.add(item.asText()));
        Map<String, SessionProjection.PendingToolInvocation> tools = new LinkedHashMap<>();
        for (JsonNode item : node.path("pendingTools")) {
            var tool = new SessionProjection.PendingToolInvocation(item.path("invocationId").asText(),
                    item.path("name").asText(null), item.path("arguments").asText(null));
            tools.put(tool.invocationId(), tool);
        }
        List<String> warnings = new ArrayList<>();
        node.path("warnings").forEach(item -> warnings.add(item.asText()));
        SessionProjection.MeasuredUsageFact fact = null;
        JsonNode usage = node.path("lastCompletedUsage");
        if (!usage.isMissingNode()) {
            fact = new SessionProjection.MeasuredUsageFact(usage.path("requestId").asText(null),
                    usage.path("provider").asText(null), usage.path("model").asText(null),
                    new MeasuredUsage(usage.path("inputTokens").asInt(), usage.path("outputTokens").asInt(),
                            usage.path("cachedInputTokens").asInt(),
                            MeasuredUsage.InputScope.valueOf(usage.path("inputScope").asText()),
                            usage.path("includesTools").asBoolean(), usage.path("includesSystem").asBoolean(),
                            usage.path("trusted").asBoolean(), Instant.ofEpochMilli(usage.path("measuredAt").asLong())));
        }
        return new SessionProjection(surface, conversation, openTurns,
                node.path("lastAppliedSequence").asLong(), node.path("historyVersion").asLong(),
                node.path("compactionGeneration").asLong(), fact, requests, tools,
                node.path("cleanlyClosed").asBoolean(), warnings);
    }

    private static String prefixHash(List<SessionEvent> events, long sequence) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i <= sequence; i++) {
                digest.update(JSON.writeValueAsBytes(events.get(i)));
                digest.update((byte) '\n');
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static long sequenceOf(Path path) {
        String name = path.getFileName().toString();
        try { return Long.parseLong(name.substring(11, name.length() - 5)); }
        catch (RuntimeException e) { return -1; }
    }

    public record SessionCheckpoint(long lastAppliedSequence, Path path) {}
    public record LoadResult(Optional<SessionCheckpoint> checkpoint, SessionProjection projection) {}
}
