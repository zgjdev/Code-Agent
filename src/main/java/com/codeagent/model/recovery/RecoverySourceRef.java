package com.codeagent.model.recovery;

/**
 * Integrity reference only; grants no path/URL authorization.
 */
public record RecoverySourceRef(
        String store,
        String recordId,
        Long sequence,
        String attemptId,
        String contentHash) {
}
