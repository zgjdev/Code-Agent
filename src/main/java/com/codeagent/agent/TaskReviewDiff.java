package com.codeagent.agent;

import java.util.List;

/** Untrusted, bounded review material. Incomplete material cannot authorize completion. */
public record TaskReviewDiff(String patch, boolean complete, List<String> issues) {
    public TaskReviewDiff {
        patch = patch == null ? "" : patch;
        issues = issues == null ? List.of() : List.copyOf(issues);
        complete = complete && issues.isEmpty();
    }

    public static TaskReviewDiff unavailable(String reason) {
        return new TaskReviewDiff("", false, List.of(reason));
    }
}
