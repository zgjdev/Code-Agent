package com.codeagent.context;

import java.io.IOException;

/** Atomic commit boundary. Callers retain execution ownership throughout commit. */
public interface ContextEditSink {
    enum CommitResult { APPLIED, ALREADY_APPLIED, STALE }
    CommitResult commit(ContextEditBatch batch) throws IOException;
}
