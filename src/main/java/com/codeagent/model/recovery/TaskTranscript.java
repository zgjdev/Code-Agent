package com.codeagent.model.recovery;

import java.util.List;

public record TaskTranscript(
        String attemptId,
        long lastSequence,
        RecoveryCompleteness completeness,
        List<RecoveredMessageGroup> groups,
        List<RecoveryFact> facts,
        List<RecoveryUnknown> unknowns,
        List<RecoveryWarning> warnings,
        List<RecoverySourceRef> sourcePrefixes) {

    public TaskTranscript(String attemptId, long lastSequence, RecoveryCompleteness completeness,
                          List<RecoveredMessageGroup> groups, List<RecoveryFact> facts,
                          List<RecoveryUnknown> unknowns, List<RecoveryWarning> warnings) {
        this(attemptId, lastSequence, completeness, groups, facts, unknowns, warnings, List.of());
    }

    public TaskTranscript {
        groups = RecoveryBounds.copy(groups, 200);
        facts = RecoveryBounds.copy(facts, 200);
        unknowns = RecoveryBounds.copy(unknowns, 200);
        warnings = RecoveryBounds.copy(warnings, 200);
        sourcePrefixes = RecoveryBounds.copy(sourcePrefixes, 200);
    }

    @Override
    public String toString() {
        return "TaskTranscript[sequence=" + lastSequence + ", completeness=" + completeness + ", groupCount=" + groups.size() + "]";
    }
}
