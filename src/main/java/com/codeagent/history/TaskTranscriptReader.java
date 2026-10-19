package com.codeagent.history;

import com.codeagent.util.SecretRedactor;
import com.codeagent.model.recovery.*;
import com.codeagent.util.RecoveryIntegrity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

/**
 * Pure controlled child projection. It does not call LLM/tools or mutate old logs.
 */
public final class TaskTranscriptReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SessionStore sessions;

    private final TaskRecoverySourceAccess attempts;

    public TaskTranscriptReader(SessionStore sessions, TaskRecoverySourceAccess attempts) {
        this.sessions = Objects.requireNonNull(sessions);
        this.attempts = Objects.requireNonNull(attempts);
    }

    public TaskTranscript read(TaskAttemptBinding source, long maxSequence) throws IOException {
        return read(source, maxSequence, new HashSet<>(), new HashMap<>());
    }

    private TaskTranscript read(TaskAttemptBinding source, long maxSequence, Set<String> stack,
                                Map<String, TaskTranscript> fullSources) throws IOException {
        try {
            attempts.requireExact(source);
            if (!stack.add(source.attemptId()) || stack.size() > 200)
                throw new IOException("Invalid recovery source chain");
            var snapshot = sessions.readSnapshot(source.childSessionId(), Path.of(source.workspace()), source.parentSessionId(), maxSequence);
            if (!("task:" + source.taskId()).equals(snapshot.manifest().actor()) || !"plan".equals(snapshot.manifest().mode()))
                throw new IOException("Task source identity mismatch");
            var checkpoint = attempts.readCheckpoint(source);
            if (checkpoint.childLastSequence() > snapshot.lastSequence())
                throw new IOException("Task checkpoint exceeds source prefix");
            Map<Long, SessionEvent> bySequence = new HashMap<>();
            for (var event : snapshot.events()) bySequence.put(event.sequence(), event);
            for (var ref : attempts.readInvocationSources(source)) {
                var event = bySequence.get(ref.sequence());
                if (event == null || !source.attemptId().equals(event.payload().path("attemptId").asText()) || !RecoveryIntegrity.hash(event.payload().toString()).equals(ref.contentHash()))
                    throw new IOException("Task fact source integrity mismatch");
            }
            var groups = new ArrayList<RecoveredMessageGroup>();
            var unknowns = new ArrayList<RecoveryUnknown>(checkpoint.unknowns());
            var warnings = new ArrayList<RecoveryWarning>();
            Map<String, RecoverySourceRef> prefixes = new TreeMap<>();
            prefixes.put(source.attemptId() + ":" + source.childSessionId(), new RecoverySourceRef(
                    "child", source.childSessionId(), snapshot.lastSequence(), source.attemptId(),
                    RecoveryIntegrity.hash(JSON.writeValueAsString(new Object[]{snapshot.events()}))));
            var facts = new LinkedHashMap<String, RecoveryFact>();
            for (var fact : checkpoint.facts()) facts.put(fact.factId(), fact);
            // A forced typed result may outlive its DB index commit; derive facts without rewriting either store.
            Map<String, SessionEvent> typedStarts = new HashMap<>();
            for (var event : snapshot.events()) {
                if ("task_recovery_typed_start".equals(event.source())) {
                    if (!source.attemptId().equals(event.payload().path("attemptId").asText()))
                        throw new IOException("Typed start ownership mismatch");
                    String key = event.payload().path("callKey").asText("");
                    if (key.isBlank() || typedStarts.putIfAbsent(key, event) != null)
                        throw new IOException("Typed start identity invalid");
                } else if ("task_recovery_typed_result".equals(event.source())) {
                    var p = event.payload();
                    String call = p.path("callKey").asText("");
                    var start = typedStarts.get(call);
                    if (start == null || start.sequence() != p.path("startSequence").asLong(-1) || !source.attemptId().equals(p.path("attemptId").asText()) || !start.payload().path("name").equals(p.path("name")) || !start.payload().path("callOrdinal").equals(p.path("callOrdinal")))
                        throw new IOException("Typed result source mismatch");
                    String tool = p.path("name").asText("");
                    boolean command = "execute_command".equals(tool);
                    Integer exit = p.path("exitCode").isIntegralNumber() ? p.path("exitCode").intValue() : null;
                    boolean timedOut = p.path("timedOut").asBoolean(), ok = p.path("successful").asBoolean();
                    var kind = command ? (timedOut || exit == null ? RecoveryFact.Kind.COMMAND_OUTCOME_UNKNOWN : ok && exit == 0 ? RecoveryFact.Kind.COMMAND_SUCCEEDED : RecoveryFact.Kind.COMMAND_FAILED) : ok ? RecoveryFact.Kind.TOOL_SUCCEEDED : RecoveryFact.Kind.TOOL_FAILED;
                    facts.put(call, new RecoveryFact(call, kind, kind.name(), event.timestamp(), RecoveryFact.VerificationClass.HISTORICAL, List.of(new RecoverySourceRef("child", source.childSessionId(), event.sequence(), source.attemptId(), RecoveryIntegrity.hash(p.toString())))));
                    if (!timedOut && (!command || exit != null))
                        unknowns.removeIf(u -> call.equals(u.callId()));
                }
            }
            // Unknown effects belong to the source attempt, not to the subset of message groups
            // imported into this child. Merge after local outcomes so a reused call key in the
            // new attempt cannot accidentally confirm an ancestor's invocation.
            boolean ancestorPartial = false;
            if (source.sourceAttemptId() != null) {
                var prior = attempts.findAttempt(source.sourceAttemptId())
                        .orElseThrow(() -> new IOException("Recovery ancestor missing"));
                if (!source.workspace().equals(prior.workspace()) || !source.parentSessionId().equals(prior.parentSessionId())
                        || !source.executionId().equals(prior.executionId()) || !source.planId().equals(prior.planId())
                        || !source.taskId().equals(prior.taskId()))
                    throw new IOException("Recovery ancestor ownership mismatch");
                var ancestor = fullSources.get(prior.attemptId());
                if (ancestor == null) {
                    ancestor = read(prior, -1, new HashSet<>(stack), fullSources);
                    fullSources.put(prior.attemptId(), ancestor);
                }
                unknowns.addAll(ancestor.unknowns());
                warnings.addAll(ancestor.warnings());
                ancestorPartial = ancestor.completeness() != RecoveryCompleteness.COMPLETE;
                for (var prefix : ancestor.sourcePrefixes())
                    prefixes.put(prefix.attemptId() + ":" + prefix.recordId(), prefix);
            }
            List<SessionEvent> pending = new ArrayList<>();
            Set<String> calls = new LinkedHashSet<>();
            Set<String> results = new HashSet<>();
            Set<String> origins = new HashSet<>();
            int filtered = 0;
            for (var event : snapshot.events()) {
                if (event.source() != null && event.source().startsWith("recovery_import")) {
                    // Imports must use an exact canonical origin from the validated source chain.
                    if (!SessionEvent.Types.ASSISTANT_MESSAGE.equals(event.type()))
                        continue;
                    var origin = event.payload().path("recoveryOrigin");
                    String originAttempt = origin.path("attemptId").asText("");
                    var prior = findSource(source, originAttempt);
                    if (prior == null)
                        throw new IOException("Recovery import origin not in source chain");
                    // A message group's range is not the source prefix watermark: typed refs and
                    // TASK_RESULT may follow even its final group. Capture/validate the entire
                    // committed source prefix first, then select the exact canonical group.
                    var original = fullSources.get(prior.attemptId());
                    if (original == null) {
                        original = read(prior, -1, new HashSet<>(stack), fullSources);
                        fullSources.put(prior.attemptId(), original);
                    }
                    for (var prefix : original.sourcePrefixes())
                        prefixes.put(prefix.attemptId() + ":" + prefix.recordId(), prefix);
                    var matched = original.groups().stream().filter(g -> g.firstSequence() == origin.path("firstSequence").asLong(-1) && g.lastSequence() == origin.path("lastSequence").asLong(-1) && g.canonicalOriginChildId().equals(origin.path("childSessionId").asText("")) && g.contentHash().equals(origin.path("contentHash").asText(""))).findFirst().orElseThrow(() -> new IOException("Recovery import origin integrity mismatch"));
                    if (origins.add(key(matched)))
                        groups.add(matched);
                    continue;
                }
                if (SessionEvent.Types.ASSISTANT_MESSAGE.equals(event.type())) {
                    if (!pending.isEmpty()) {
                        unknowns.add(unknown(source, calls, results));
                        pending.clear();
                        calls.clear();
                        results.clear();
                    }
                    JsonNode message = event.payload().path("message");
                    JsonNode toolCalls = message.path("toolCalls");
                    if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                        if (toolCalls.size() > 64)
                            throw new IOException("Task tool group exceeds bound");
                        for (var call : toolCalls) {
                            String id = call.path("id").asText("");
                            if (id.isBlank() || !calls.add(id))
                                throw new IOException("Invalid task tool group");
                        }
                        pending.add(event);
                    } else {
                        var group = group(source, List.of(event));
                        if (group == null)
                            filtered++;
                        else if (origins.add(key(group)))
                            groups.add(group);
                    }
                } else if (SessionEvent.Types.TOOL_RESULT.equals(event.type())) {
                    if (!event.payload().path("message").isObject())
                        continue;
                    String call = event.payload().path("message").path("toolCallId").asText("");
                    if (!calls.contains(call) || !results.add(call)) {
                        filtered++;
                        continue;
                    }
                    pending.add(event);
                    if (results.size() == calls.size()) {
                        var group = group(source, pending);
                        if (group == null)
                            filtered++;
                        else if (origins.add(key(group)))
                            groups.add(group);
                        pending.clear();
                        calls.clear();
                        results.clear();
                    }
                }
                if (groups.size() > 200 || unknowns.size() > 200)
                    throw new IOException("Task transcript exceeds bound");
            }
            if (!pending.isEmpty())
                unknowns.add(unknown(source, calls, results));
            if (filtered > 0)
                warnings.add(new RecoveryWarning(RecoveryWarning.Code.SOURCE_FILTERED, source.taskId(), filtered));
            var completeness = filtered > 0 || ancestorPartial || !unknowns.isEmpty()
                    ? RecoveryCompleteness.PARTIAL : RecoveryCompleteness.COMPLETE;
            return new TaskTranscript(source.attemptId(), snapshot.lastSequence(), completeness, groups,
                    List.copyOf(facts.values()), unknowns, warnings, List.copyOf(prefixes.values()));
        } catch (SQLException | RuntimeException e) {
            throw new IOException("Task recovery source unavailable");
        }
    }

    private TaskAttemptBinding findSource(TaskAttemptBinding current, String attempt) throws SQLException {
        String id = current.sourceAttemptId();
        int depth = 0;
        while (id != null && depth++ < 200) {
            var found = attempts.findAttempt(id).orElseThrow(() -> new SQLException("Source missing"));
            if (id.equals(attempt))
                return found;
            id = found.sourceAttemptId();
        }
        return null;
    }

    private static String key(RecoveredMessageGroup g) {
        return g.canonicalOriginChildId() + ":" + g.firstSequence() + ":" + g.lastSequence() + ":" + g.contentHash();
    }

    private static RecoveryUnknown unknown(TaskAttemptBinding source, Set<String> calls, Set<String> results) {
        String call = calls.stream().filter(c -> !results.contains(c)).findFirst().orElse(null);
        return new RecoveryUnknown(RecoveryUnknown.Kind.INVOCATION_OUTCOME_UNKNOWN, source.taskId(), call, "Incomplete assistant/tool group", "Verify effect before retry");
    }

    private RecoveredMessageGroup group(TaskAttemptBinding source, List<SessionEvent> events) {
        var messages = new ArrayList<RecoveryMessage>();
        StringBuilder canonical = new StringBuilder();
        for (var event : events) {
            var m = event.payload().path("message");
            String role = m.path("role").asText("");
            if (!List.of("assistant", "tool").contains(role))
                return null;
            JsonNode content = m.get("content");
            if (content != null && !content.isNull() && !content.isTextual())
                return null;
            String text = content == null || content.isNull() ? null : content.asText();
            if (text == null && !m.path("toolCalls").isArray())
                return null;
            if (!safe(text))
                return null;
            var calls = new ArrayList<RecoveryMessage.ToolCall>();
            if (m.path("toolCalls").isArray())
                for (var call : m.path("toolCalls")) {
                    String args = call.path("function").path("arguments").asText("");
                    String name = call.path("function").path("name").asText("");
                    if (args.length() > 32768 || !safe(args) || !safe(name))
                        return null;
                    calls.add(new RecoveryMessage.ToolCall(call.path("id").asText(""), name, args));
                }
            if (text != null && text.length() > 32768)
                return null;
            messages.add(new RecoveryMessage(role, text, m.path("toolCallId").asText(null), calls));
            // Only controlled fields participate; reasoning/system/attachments are never serialized.
            canonical.append(event.sequence()).append(':').append(role).append(':').append(text).append(':');
            for (var call : calls) canonical.append(call.id()).append(':').append(call.name()).append(':').append(call.arguments()).append(';');
            canonical.append(m.path("toolCallId").asText("")).append('\n');
        }
        return new RecoveredMessageGroup(source.childSessionId(), source.attemptId(), events.get(0).sequence(), events.get(events.size() - 1).sequence(), RecoveryIntegrity.hash(canonical.toString()), RecoveryCompleteness.COMPLETE, messages, false);
    }

    private static boolean safe(String text) {
        return text == null || text.equals(SecretRedactor.redact(text)) && text.codePoints().noneMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t' || c == 127);
    }
}
