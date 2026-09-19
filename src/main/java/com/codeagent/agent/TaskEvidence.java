package com.codeagent.agent;

import com.codeagent.plan.EvidenceType;

import java.util.List;

public record TaskEvidence(EvidenceType type,
                           EvidenceStatus status,
                           String summary,
                           List<String> relatedPaths,
                           String source,
                           long observedAt) {
    public TaskEvidence {
        type = type == null ? EvidenceType.TOOL_RESULT : type;
        status = status == null ? EvidenceStatus.MISSING : status;
        summary = bounded(summary);
        relatedPaths = relatedPaths == null ? List.of() : List.copyOf(relatedPaths);
        source = bounded(source);
    }

    public static TaskEvidence passed(EvidenceType type, String summary, String source) {
        return new TaskEvidence(type, EvidenceStatus.PASSED, summary, List.of(), source,
                System.currentTimeMillis());
    }

    private static String bounded(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }
}
