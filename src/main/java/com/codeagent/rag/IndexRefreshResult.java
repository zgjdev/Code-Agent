package com.codeagent.rag;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Lexical outcome; ready paths are relative files safely committed or confirmed unchanged in this batch. */
public record IndexRefreshResult(
        int changedFiles,
        int unchangedFiles,
        int deletedFiles,
        int failedFiles,
        List<String> reasonCodes,
        Set<Path> embeddingReadyPaths
) {
    public IndexRefreshResult(int changedFiles, int unchangedFiles, int deletedFiles, int failedFiles, List<String> reasonCodes) {
        this(changedFiles, unchangedFiles, deletedFiles, failedFiles, reasonCodes, Set.of());
    }

    public IndexRefreshResult {
        reasonCodes = List.copyOf(reasonCodes);
        embeddingReadyPaths = Set.copyOf(embeddingReadyPaths);
    }
}
