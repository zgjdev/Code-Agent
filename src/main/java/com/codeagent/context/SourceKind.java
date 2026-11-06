package com.codeagent.context;

/** Provenance assigned by the host from the write path or validated ledger metadata. */
public enum SourceKind {
    SYSTEM, PERMISSION, REAL_USER, ASSISTANT, TOOL_CALL, TOOL_RESULT, SYNTHETIC,
    RECOVERY_CONTROL, REVIEW_EVIDENCE, PROVIDER_OPAQUE, LOCAL_SUMMARY, GLOBAL_SUMMARY, LEGACY_UNKNOWN
}
