package com.codeagent.history;

import java.util.List;

/** Diagnostics produced while turning an interrupted session into a writable surface. */
public record SessionResumeResult(
        String sessionId,
        boolean interrupted,
        int recoveredRequests,
        int recoveredToolCalls,
        List<String> warnings) {

    public SessionResumeResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
