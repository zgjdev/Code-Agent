package com.codeagent.context;

import com.codeagent.tool.ToolOutput.FailureKind;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Host facts stored alongside a tool result. Null fields represent unavailable facts. */
public record ToolResultDescriptor(String invocationId, String toolName, OutcomeSource outcomeSource,
                                   ProcessOutcome processOutcome, Boolean successful, Integer exitCode,
                                   String executedCommand, FailureKind failureKind, boolean observationUnknown,
                                   CaptureCompleteness captureCompleteness, Map<String, String> resourceLocation,
                                   Integer resultCount, String contentHash, List<String> evidenceRefs,
                                   long elapsedMillis) {
    public enum OutcomeSource { TYPED, LEGACY_INFERRED, UNKNOWN }
    public enum ProcessOutcome { SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, DENIED, UNKNOWN }
    public enum CaptureCompleteness { COMPLETE, TRUNCATED, READ_TIMEOUT, UNKNOWN }

    public ToolResultDescriptor {
        Objects.requireNonNull(outcomeSource);
        Objects.requireNonNull(processOutcome);
        Objects.requireNonNull(captureCompleteness);
        resourceLocation = resourceLocation == null ? Map.of() : Map.copyOf(resourceLocation);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        if (outcomeSource != OutcomeSource.TYPED && (processOutcome != ProcessOutcome.UNKNOWN
                || successful != null || exitCode != null || executedCommand != null || failureKind != null
                || captureCompleteness != CaptureCompleteness.UNKNOWN || !resourceLocation.isEmpty()
                || resultCount != null || !evidenceRefs.isEmpty())) {
            throw new IllegalArgumentException("Unverified tool result cannot contain typed execution facts");
        }
    }

    public static ToolResultDescriptor unknown(String invocationId, String toolName, String text) {
        return unverified(invocationId, toolName, OutcomeSource.UNKNOWN, text, 0);
    }

    public static ToolResultDescriptor unverified(String invocationId, String toolName, OutcomeSource source,
                                                  String text, long elapsedMillis) {
        return unverified(invocationId, toolName, source, text, elapsedMillis, false);
    }

    public static ToolResultDescriptor unverified(String invocationId, String toolName, OutcomeSource source,
                                                  String text, long elapsedMillis, boolean observationUnknown) {
        return new ToolResultDescriptor(invocationId, toolName, source, ProcessOutcome.UNKNOWN, null, null,
                null, null, observationUnknown, CaptureCompleteness.UNKNOWN, Map.of(), null,
                ContextDigests.textDigest(Objects.toString(text, "")), List.of(), elapsedMillis);
    }
}
