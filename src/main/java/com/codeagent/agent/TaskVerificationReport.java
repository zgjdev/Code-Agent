package com.codeagent.agent;

import java.util.List;

public record TaskVerificationReport(VerificationOutcome outcome,
                                     List<String> acceptanceCriteria,
                                     List<TaskEvidence> evidence,
                                     List<String> blockingReasons) {
    public TaskVerificationReport {
        outcome = outcome == null ? VerificationOutcome.UNAVAILABLE : outcome;
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
    }
}
