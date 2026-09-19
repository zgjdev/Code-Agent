package com.codeagent.agent;

import com.codeagent.plan.EvidenceType;
import com.codeagent.plan.Task;

import java.util.ArrayList;
import java.util.List;

public final class DeterministicEvidenceGate {
    private DeterministicEvidenceGate() {
    }

    public static TaskVerificationReport evaluate(Task task, List<TaskEvidence> evidence) {
        List<TaskEvidence> observed = evidence == null ? List.of() : List.copyOf(evidence);
        List<EvidenceType> required = task == null || task.getRequiredEvidence() == null
                ? List.of() : List.copyOf(task.getRequiredEvidence());
        if (required.isEmpty()) {
            return report(VerificationOutcome.NOT_REQUIRED, task, observed, List.of());
        }

        List<String> blocking = new ArrayList<>();
        for (EvidenceType type : required) {
            boolean passed = observed.stream().anyMatch(item -> item.type() == type
                    && item.status() == EvidenceStatus.PASSED);
            if (passed) {
                continue;
            }
            boolean failed = observed.stream().anyMatch(item -> item.type() == type
                    && item.status() == EvidenceStatus.FAILED);
            blocking.add(type + (failed ? " evidence failed" : " evidence missing"));
        }
        return report(blocking.isEmpty() ? VerificationOutcome.VERIFIED : VerificationOutcome.REJECTED,
                task, observed, blocking);
    }

    private static TaskVerificationReport report(VerificationOutcome outcome, Task task,
                                                 List<TaskEvidence> evidence, List<String> blocking) {
        return new TaskVerificationReport(outcome,
                task == null ? List.of() : task.getAcceptanceCriteria(), evidence, blocking);
    }
}
