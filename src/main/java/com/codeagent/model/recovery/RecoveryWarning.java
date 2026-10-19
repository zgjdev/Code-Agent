package com.codeagent.model.recovery;

public record RecoveryWarning(Code code, String relatedId, int omittedCount) {

    public enum Code {

        LEGACY_UNBOUND,
        METADATA_ONLY,
        SOURCE_UNAVAILABLE,
        SOURCE_CHANGED,
        SOURCE_FILTERED,
        STATE_UNAVAILABLE,
        TRUNCATED
    }
}
