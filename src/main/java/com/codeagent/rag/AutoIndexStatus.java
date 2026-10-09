package com.codeagent.rag;

/** Process-local hints; idle never certifies the entire repository is fresh. */
public record AutoIndexStatus(String state, int pendingLexical, int pendingEmbedding,
                              long lastCompleteReconcileMillis, String watcherState, String errorCode) {
    public static AutoIndexStatus disabled() { return new AutoIndexStatus("disabled", 0, 0, 0, "off", ""); }
}
