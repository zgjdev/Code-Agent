package com.codeagent.runtime.execution;

/** Throw only before task/model/tool effects, after physical child/snapshot writers have drained. */
public final class RecoveryPreparationDeferredException extends Exception {
    public RecoveryPreparationDeferredException(String diagnostic) { super(diagnostic); }
    public RecoveryPreparationDeferredException(String diagnostic, Throwable cause) { super(diagnostic, cause); }
}
