package com.codeagent.agent;

import com.codeagent.plan.EvidenceType;
import com.codeagent.plan.Task;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicEvidenceGateTest {
    @Test
    void missingRequiredEvidenceIsRejectedEvenWhenResultClaimsSuccess() {
        Task task = task(Set.of(EvidenceType.TEST));
        TaskVerificationReport report = DeterministicEvidenceGate.evaluate(task, List.of());

        assertEquals(VerificationOutcome.REJECTED, report.outcome());
        assertTrue(report.blockingReasons().stream().anyMatch(reason -> reason.contains("TEST")));
    }

    @Test
    void everyRequiredEvidenceNeedsObservedPassedItem() {
        Task task = task(Set.of(EvidenceType.TEST, EvidenceType.BUILD));
        List<TaskEvidence> evidence = List.of(
                TaskEvidence.passed(EvidenceType.TEST, "mvn test", "tool"));

        TaskVerificationReport report = DeterministicEvidenceGate.evaluate(task, evidence);

        assertEquals(VerificationOutcome.REJECTED, report.outcome());
        assertTrue(report.blockingReasons().stream().anyMatch(reason -> reason.contains("BUILD")));
    }

    @Test
    void noRequirementsAreNotRequired() {
        TaskVerificationReport report = DeterministicEvidenceGate.evaluate(task(Set.of()), List.of());

        assertEquals(VerificationOutcome.NOT_REQUIRED, report.outcome());
    }

    private static Task task(Set<EvidenceType> required) {
        return new Task("task", "verify", Task.TaskType.VERIFICATION, List.of(), null, List.of(), required);
    }
}
