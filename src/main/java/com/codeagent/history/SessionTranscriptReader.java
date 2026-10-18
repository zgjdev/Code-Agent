package com.codeagent.history;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Human history projection; never uses the compacted model surface or returns raw payloads. */
public final class SessionTranscriptReader {
    public static final int CHUNK_CODE_POINTS = 4096;
    public static final int DEFAULT_PAGE_SIZE = 30;
    private final SessionStore store;
    public SessionTranscriptReader(SessionStore store) { this.store = Objects.requireNonNull(store); }
    public TranscriptPage readPage(String sessionId, Path workspace, long afterSequence, int limit,
                                   long snapshotLastSequence) throws IOException {
        return readPage(sessionId, workspace, new TranscriptCursor(afterSequence, 0), limit, snapshotLastSequence, false);
    }
    public TranscriptPage readPage(String sessionId, Path workspace, TranscriptCursor cursor, int limit,
                                   long snapshotLastSequence, boolean includeCleared) throws IOException {
        Objects.requireNonNull(cursor, "cursor");
        int pageSize = limit == 0 ? DEFAULT_PAGE_SIZE : limit;
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("page size must be 1..100");
        SessionReadSnapshot snapshot = store.readParentSnapshot(sessionId, workspace, snapshotLastSequence);
        if (cursor.afterSequence() > snapshot.lastSequence()) throw new IllegalArgumentException("cursor exceeds snapshot");
        List<TranscriptEntry> entries = project(snapshot);
        long lastClear = entries.stream().filter(e -> e.boundaryKind() == TranscriptEntry.BoundaryKind.CLEAR)
                .mapToLong(TranscriptEntry::sequence).max().orElse(-1);
        List<TranscriptEntry> visible = entries.stream()
                .filter(e -> includeCleared || e.sequence() >= lastClear).toList();
        if (cursor.textOffset() > 0 && visible.stream().noneMatch(e -> e.sequence() == cursor.afterSequence())) {
            throw new IllegalArgumentException("chunk cursor has no visible event");
        }
        List<TranscriptEntry> chunks = new ArrayList<>();
        for (TranscriptEntry entry : visible) {
            if (entry.sequence() < cursor.afterSequence()
                    || (entry.sequence() == cursor.afterSequence() && cursor.textOffset() == 0)) continue;
            int count = entry.text().codePointCount(0, entry.text().length());
            int offset = entry.sequence() == cursor.afterSequence() ? cursor.textOffset() : 0;
            if (offset >= count && offset != 0) throw new IllegalArgumentException("cursor offset exceeds text");
            if (offset != 0 && offset % CHUNK_CODE_POINTS != 0) throw new IllegalArgumentException("invalid chunk offset");
            for (; offset < count; offset += CHUNK_CODE_POINTS) {
                int end = Math.min(count, offset + CHUNK_CODE_POINTS);
                String text = entry.text().substring(entry.text().offsetByCodePoints(0, offset),
                        entry.text().offsetByCodePoints(0, end));
                chunks.add(new TranscriptEntry(entry.sequence(), entry.role(), entry.mode(), text,
                        entry.boundaryKind(), offset, end < count));
                if (chunks.size() == pageSize) {
                    TranscriptCursor next = end < count ? new TranscriptCursor(entry.sequence(), end)
                            : visible.stream().anyMatch(e -> e.sequence() > entry.sequence())
                            ? new TranscriptCursor(entry.sequence(), 0) : null;
                    return new TranscriptPage(chunks, next, snapshot.lastSequence(), lastClear >= 0);
                }
            }
        }
        return new TranscriptPage(chunks, null, snapshot.lastSequence(), lastClear >= 0);
    }

    static List<TranscriptEntry> project(SessionReadSnapshot snapshot) {
        List<SessionEvent> events = snapshot.events();
        Map<String, SessionEvent> requests = new HashMap<>();
        Set<String> completed = new HashSet<>();
        Map<String, SessionEvent> failures = new HashMap<>();
        Set<String> fragments = new HashSet<>();
        Map<String, SessionProjection.ExecutionEnvelope> executions =
                new SessionReplayer().replay(snapshot.manifest(), events).executionEnvelopes();
        Map<String, Set<String>> requestExecutions = new HashMap<>();
        Set<String> reactUserExecutions = new HashSet<>();
        Set<String> answeredExecutions = new HashSet<>();
        Set<String> interruptedExecutions = new HashSet<>();
        Map<String, SessionEvent> openPlanTurns = new HashMap<>();
        Set<String> answeredPlanTurns = new HashSet<>();
        for (SessionEvent event : events) {
            if (SessionEvent.Types.SURFACE_CLEAR.equals(event.type())) openPlanTurns.clear();
            if (topLevelSource(event) && "plan".equals(event.mode())) {
                String turn = text(event.payload(), "turnId");
                if (turn != null && SessionEvent.Types.TURN_START.equals(event.type())) openPlanTurns.putIfAbsent(turn, event);
                if (turn != null && SessionEvent.Types.TURN_END.equals(event.type())) openPlanTurns.remove(turn);
            }
            String request = text(event.payload(), "requestId");
            if (request == null || !topLevelSource(event)) continue;
            if (SessionEvent.Types.REQUEST_STARTED.equals(event.type())) requests.put(request, event);
            if (SessionEvent.Types.REQUEST_FINISHED.equals(event.type()) && sameSource(requests.get(request), event)) completed.add(request);
            if (SessionEvent.Types.REQUEST_FAILED.equals(event.type()) && sameSource(requests.get(request), event)) failures.put(request, event);
            if (sameSource(requests.get(request), event)
                    && (SessionEvent.Types.REQUEST_STARTED.equals(event.type())
                    || SessionEvent.Types.REQUEST_FINISHED.equals(event.type())
                    || SessionEvent.Types.REQUEST_FAILED.equals(event.type()))) {
                bindRequestExecution(requestExecutions, request, executionId(event, executions));
            }
        }
        List<TranscriptEntry> result = new ArrayList<>();
        for (SessionEvent event : events) {
            if (SessionEvent.Types.SURFACE_CLEAR.equals(event.type())) {
                result.add(boundary(event.sequence(), TranscriptEntry.BoundaryKind.CLEAR, "历史已清空；可主动展开更早历史"));
                continue;
            }
            if (SessionEvent.Types.COMPACTION_END.equals(event.type()) && "completed".equals(text(event.payload(), "status"))) {
                result.add(boundary(event.sequence(), TranscriptEntry.BoundaryKind.COMPACTION, "上下文已压缩；原始对话仍可查看"));
                continue;
            }
            if (!topLevelSource(event) || text(event.payload(), "compactionId") != null) continue;
            JsonNode conversation = event.payload() == null ? null : event.payload().get("conversation");
            String role = text(conversation, "role");
            String mode = text(conversation, "mode");
            String content = text(conversation, "content");
            boolean user = "user".equals(role) && SessionEvent.Types.USER_MESSAGE.equals(event.type());
            boolean assistant = "assistant".equals(role) && SessionEvent.Types.ASSISTANT_MESSAGE.equals(event.type());
            if ((!user && !assistant) || !event.mode().equals(mode) || content == null) continue;
            String request = text(event.payload(), "requestId");
            boolean fragment = assistant && request != null && !completed.contains(request);
            if (request != null && !sameSource(requests.get(request), event)) continue;
            String execution = executionId(event, executions);
            if (assistant && request != null) bindRequestExecution(requestExecutions, request, execution);
            String display = TranscriptDisplayText.sanitize(content);
            JsonNode parts = event.payload().path("message").path("contentParts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if ("image_base64".equals(text(part, "type")) || "image_url".equals(text(part, "type"))) display += "\n[图片]";
                }
            }
            if (display.isBlank()) continue;
            if (fragment) fragments.add(request);
            if (execution != null) {
                SessionProjection.ExecutionEnvelope envelope = executions.get(execution);
                if (user && Objects.equals(envelope.userSequence(), event.sequence())) reactUserExecutions.add(execution);
                if (assistant && !fragment && Objects.equals(envelope.assistantSequence(), event.sequence())) answeredExecutions.add(execution);
                if (fragment) interruptedExecutions.add(execution);
            }
            if (assistant && !fragment && "plan".equals(mode)) answeredPlanTurns.add(text(conversation, "turnId"));
            result.add(new TranscriptEntry(event.sequence(), user ? TranscriptEntry.Role.USER : TranscriptEntry.Role.ASSISTANT,
                    "react".equals(mode) ? TranscriptEntry.Mode.REACT : TranscriptEntry.Mode.PLAN, display,
                    fragment ? TranscriptEntry.BoundaryKind.INTERRUPTED_FRAGMENT : TranscriptEntry.BoundaryKind.MESSAGE, 0, false));
        }
        for (var request : requests.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().sequence())).toList()) {
            if (!completed.contains(request.getKey()) && !fragments.contains(request.getKey())) {
                Set<String> identities = requestExecutions.getOrDefault(request.getKey(), Set.of());
                String execution = identities.size() == 1 ? identities.iterator().next() : null;
                if (execution != null && (answeredExecutions.contains(execution) || !interruptedExecutions.add(execution))) continue;
                SessionEvent event = failures.getOrDefault(request.getKey(), request.getValue());
                result.add(boundary(event.sequence(), TranscriptEntry.BoundaryKind.INTERRUPTED, "本轮中断，未保存完整回答"));
            }
        }
        for (String execution : reactUserExecutions) {
            if (!answeredExecutions.contains(execution) && interruptedExecutions.add(execution)) {
                SessionProjection.ExecutionEnvelope envelope = executions.get(execution);
                result.add(boundary(envelope.ended() ? envelope.endSequence() : envelope.startSequence(),
                        TranscriptEntry.BoundaryKind.INTERRUPTED, "本轮中断，未保存完整回答"));
            }
        }
        for (var turn : openPlanTurns.entrySet()) {
            if (!answeredPlanTurns.contains(turn.getKey())) {
                result.add(boundary(turn.getValue().sequence(), TranscriptEntry.BoundaryKind.INTERRUPTED,
                        "计划轮次中断，未保存完整回答"));
            }
        }
        result.sort(Comparator.comparingLong(TranscriptEntry::sequence));
        return List.copyOf(result);
    }

    private static TranscriptEntry boundary(long sequence, TranscriptEntry.BoundaryKind kind, String text) {
        return new TranscriptEntry(sequence, TranscriptEntry.Role.CONTEXT, TranscriptEntry.Mode.CONTEXT, text, kind, 0, false);
    }

    private static boolean topLevelSource(SessionEvent event) {
        return ("react".equals(event.mode()) && "agent".equals(event.actor()) && "agent".equals(event.source()))
                || ("react".equals(event.mode()) && "continuation-control".equals(event.actor()) && "continuation-control".equals(event.source()))
                || ("plan".equals(event.mode()) && "plan-agent".equals(event.actor()) && "plan-conversation".equals(event.source()));
    }

    private static boolean sameSource(SessionEvent left, SessionEvent right) {
        return left != null && Objects.equals(left.actor(), right.actor()) && Objects.equals(left.source(), right.source())
                && Objects.equals(left.mode(), right.mode());
    }

    private static void bindRequestExecution(Map<String, Set<String>> requests, String request, String execution) {
        if (execution != null) requests.computeIfAbsent(request, ignored -> new HashSet<>()).add(execution);
    }

    /** Missing request metadata stays unbound; event proximity is not execution identity. */
    private static String executionId(SessionEvent event, Map<String, SessionProjection.ExecutionEnvelope> executions) {
        if (!"react".equals(event.mode())) return null;
        String id = text(event.payload(), "executionId");
        String conversationId = event.payload() == null ? null : text(event.payload().get("conversation"), "executionId");
        if (id != null && conversationId != null && !id.equals(conversationId)) return null;
        if (id == null) id = conversationId;
        if (id == null) return null;
        SessionProjection.ExecutionEnvelope envelope = executions.get(id);
        // Incomplete request fragments have no committed assistantSequence yet; explicit owner
        // metadata can identify them, but only the committed envelope sequence marks a final answer.
        if (envelope != null && SessionEvent.Types.USER_MESSAGE.equals(event.type())
                && !Objects.equals(envelope.userSequence(), event.sequence())) return null;
        return envelope != null && event.sequence() > envelope.startSequence()
                && (!envelope.ended() || event.sequence() < envelope.endSequence()) ? id : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.path(field).isTextual()) return null;
        String value = node.path(field).textValue();
        return value.isBlank() ? null : value;
    }
}
