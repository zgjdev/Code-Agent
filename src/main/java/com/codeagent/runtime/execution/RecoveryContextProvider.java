package com.codeagent.runtime.execution;

import com.codeagent.history.*;
import com.codeagent.model.recovery.*;
import com.codeagent.plan.*;
import com.codeagent.util.RecoveryIntegrity;
import com.codeagent.util.SecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Cross-store read view captured only inside the caller's owned mutation/session lease boundary.
 * The verifier must attest owner, mutationGate and parent Session lease on the calling thread.
 * No database transaction survives this method and no models/tools are invoked.
 */
public final class RecoveryContextProvider {

    @FunctionalInterface
    public interface BoundaryVerifier {

        void verify(WorkspaceIdentity workspace, String parentSessionId, ExecutionClaim currentClaim) throws IOException;
    }

    public record ReadBoundary(
        WorkspaceIdentity workspace,
        String parentSessionId,
        ExecutionClaim currentClaim) {

        public ReadBoundary {
            Objects.requireNonNull(workspace);
            Objects.requireNonNull(parentSessionId);
            Objects.requireNonNull(currentClaim);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RuntimeExecutionStore executions;

    private final PlanStateStore plans;

    private final SessionStore sessions;

    private final TaskRecoveryStore attempts;

    private final BoundaryVerifier verifier;

    public RecoveryContextProvider(RuntimeExecutionStore executions, PlanStateStore plans, SessionStore sessions, TaskRecoveryStore attempts, BoundaryVerifier verifier) {
        this.executions = Objects.requireNonNull(executions);
        this.plans = Objects.requireNonNull(plans);
        this.sessions = Objects.requireNonNull(sessions);
        this.attempts = Objects.requireNonNull(attempts);
        this.verifier = Objects.requireNonNull(verifier);
    }

    public RecoveryContextSnapshot readRecoveryContext(ReadBoundary boundary) throws IOException {
        for (int capture = 0; capture < 2; capture++) {
            verify(boundary);
            try {
                var parent = sessions.readParentSnapshot(boundary.parentSessionId(), boundary.workspace().canonicalRoot(), -1);
                var executionRows = executions.listRecoveryState(boundary.workspace().canonicalRoot(), boundary.parentSessionId());
                var planRows = plans.readRecoveryState(boundary.workspace().canonicalRoot(), boundary.parentSessionId());
                var warningRows = new ArrayList<RecoveryWarning>();
                Map<String, String> sourceFingerprints = new TreeMap<>();
                var composed = new ArrayList<RecoveryPlanState>();
                Map<String, RuntimeExecution> byId = new HashMap<>();
                for (var e : executionRows) byId.put(e.id(), e);
                for (var p : planRows) {
                    if (p.executionId() == null) {
                        if (p.unfinished())
                            warningRows.add(new RecoveryWarning(RecoveryWarning.Code.LEGACY_UNBOUND, p.planId(), 0));
                        composed.add(p);
                        continue;
                    }
                    var e = byId.get(p.executionId());
                    // Current request may be shown, but only held original tasks become continuation candidates.
                    if (e == null)
                        continue;
                    var tasks = new ArrayList<RecoveryTaskState>();
                    for (var t : p.tasks()) {
                        var bindings = attempts.findTaskAttempts(boundary.workspace().canonicalRoot(), boundary.parentSessionId(), p.executionId(), p.planId(), t.taskId());
                        if (bindings.isEmpty()) {
                            tasks.add(t);
                            continue;
                        }
                        var a = bindings.get(bindings.size() - 1);
                        try {
                            var transcript = new TaskTranscriptReader(sessions, attempts).read(a, -1);
                            var refs = new ArrayList<>(t.sources());
                            // Include every prefix actually read, including intermediate/canonical
                            // ancestor imports; latest-child bytes alone cannot identify that view.
                            var capturedSources = new TreeMap<String, String>();
                            for (var ref : transcript.sourcePrefixes()) {
                                var binding = attempts.findAttempt(ref.attemptId()).orElseThrow(() -> new SQLException("Attempt source missing"));
                                var cp = attempts.readCheckpoint(binding);
                                capturedSources.put(ref.attemptId() + ":" + ref.recordId(), fingerprint(cp, ref.contentHash()));
                                refs.add(ref);
                            }
                            sourceFingerprints.putAll(capturedSources);
                            tasks.add(new RecoveryTaskState(t.planId(), t.taskId(), t.ordinal(), t.description(), t.recordedStatus(), t.recoveryStatus(), t.dependencies(), t.recordedResult(), transcript.facts(), transcript.unknowns(), refs, transcript.completeness()));
                            warningRows.addAll(transcript.warnings());
                        } catch (IOException | SQLException failure) {
                            warningRows.add(new RecoveryWarning(RecoveryWarning.Code.SOURCE_UNAVAILABLE, t.taskId(), 0));
                            tasks.add(t);
                        }
                    }
                    composed.add(new RecoveryPlanState(p.planId(), p.executionId(), p.goal(), p.recordedStatus(), p.updatedAt(), tasks));
                }
                var states = executionRows.stream().map(e -> new RecoveryExecutionState(e.id(), e.explicitMode() == null ? null : e.explicitMode().name(), e.selectedMode() == null ? null : e.selectedMode().name(), e.status().name(), e.dispatchState(), e.recoveryGeneration(), composed.stream().anyMatch(p -> e.id().equals(p.executionId()) && p.unfinished()), !e.id().equals(boundary.currentClaim().executionId()) && e.awaitingInput() && "task".equals(e.requestKind()), SecretRedactor.sanitize(e.submittedInput()))).toList();
                String fingerprint = fingerprint(executionFingerprint(executionRows), planRows, parent.events(), composed, sourceFingerprints);
                verify(boundary);
                var afterParent = sessions.readParentSnapshot(boundary.parentSessionId(), boundary.workspace().canonicalRoot(), -1);
                var afterExecutions = executions.listRecoveryState(boundary.workspace().canonicalRoot(), boundary.parentSessionId());
                var afterPlans = plans.readRecoveryState(boundary.workspace().canonicalRoot(), boundary.parentSessionId());
                boolean same = parent.lastSequence() == afterParent.lastSequence() && fingerprint(executionFingerprint(executionRows), planRows, parent.events(), List.of()).equals(fingerprint(executionFingerprint(afterExecutions), afterPlans, afterParent.events(), List.of()));
                if (same)
                    for (var p : composed) for (var t : p.tasks()) for (var ref : t.sources()) if ("child".equals(ref.store())) {
                        var binding = attempts.findAttempt(ref.attemptId()).orElseThrow(() -> new SQLException("Attempt source missing"));
                        var prefix = sessions.readSnapshot(ref.recordId(), boundary.workspace().canonicalRoot(), boundary.parentSessionId(), -1);
                        var checkpoint = attempts.readCheckpoint(binding);
                        String prefixHash = fingerprint(prefix.events());
                        if (prefix.lastSequence() != ref.sequence() || checkpoint.childLastSequence() > ref.sequence()
                                || !prefixHash.equals(ref.contentHash())
                                || !fingerprint(checkpoint, prefixHash).equals(sourceFingerprints.get(binding.attemptId() + ":" + binding.childSessionId())))
                            same = false;
                    }
                if (same)
                    return new RecoveryContextSnapshot(boundary.workspace().key().value(), boundary.parentSessionId(), System.currentTimeMillis(), parent.lastSequence(), fingerprint, states, composed, warningRows);
            } catch (SQLException | RuntimeException failure) {
                throw new IOException("Recovery context unavailable");
            }
        }
        throw new IOException("Recovery context changed during capture");
    }

    public boolean isCurrent(RecoveryContextSnapshot previous, ReadBoundary boundary) throws IOException {
        if (!previous.workspaceKey().equals(boundary.workspace().key().value()) || !previous.parentSessionId().equals(boundary.parentSessionId()))
            return false;
        return previous.stateFingerprint().equals(readRecoveryContext(boundary).stateFingerprint());
    }

    private void verify(ReadBoundary b) throws IOException {
        // Called before any source read. This callback is mandatory; it is never a best-effort hint.
        verifier.verify(b.workspace(), b.parentSessionId(), b.currentClaim());
        if (!new WorkspaceIdentityResolver().resolve(b.workspace().canonicalRoot()).equals(b.workspace()))
            throw new IOException("Recovery workspace identity changed");
        try {
            var current = executions.find(b.currentClaim().executionId()).orElseThrow(() -> new IOException("Recovery claim unavailable"));
            if (current.terminal() || current.status() != ExecutionStatus.RUNNING || !current.claim().equals(b.currentClaim()) || !current.workspaceKey().equals(b.workspace().key()) || !current.sessionId().equals(b.parentSessionId()))
                throw new IOException("Recovery claim identity mismatch");
        } catch (SQLException | IllegalStateException e) {
            throw new IOException("Recovery claim unavailable");
        }
    }

    private static String fingerprint(Object... data) throws IOException {
        try {
            return RecoveryIntegrity.hash(JSON.writeValueAsString(data));
        } catch (Exception e) {
            throw new IOException("Recovery fingerprint unavailable");
        }
    }

    private static List<List<Object>> executionFingerprint(List<RuntimeExecution> rows) {
        return rows.stream().map(e -> Arrays.<Object>asList(e.id(), e.workspaceKey().value(), e.sessionId(), e.status().name(), e.explicitMode(), e.selectedMode(), e.requestKind(), e.dispatchState(), e.recoveryGeneration(), e.ownerId(), e.attempt(), e.updatedAt().toString(), RecoveryIntegrity.hash(e.submittedInput()))).toList();
    }
}
