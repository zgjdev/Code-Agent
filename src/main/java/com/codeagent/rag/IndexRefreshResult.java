package com.codeagent.rag;

import java.util.List;

public record IndexRefreshResult(
        int changedFiles,
        int unchangedFiles,
        int deletedFiles,
        int failedFiles,
        List<String> reasonCodes
) {
    public IndexRefreshResult {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
