package com.codeagent.model.recovery;

import java.util.List;

public record RecoveryFact(
        String factId,
        Kind kind,
        String displayText,
        long observedAt,
        VerificationClass verificationClass,
        List<RecoverySourceRef> sourceRefs) {

    public enum Kind {

        TOOL_SUCCEEDED,
        TOOL_FAILED,
        COMMAND_SUCCEEDED,
        COMMAND_FAILED,
        COMMAND_OUTCOME_UNKNOWN,
        ASSISTANT_CLAIM
    }

    public enum VerificationClass {

        RECORDED, HISTORICAL, REVALIDATED, ASSISTANT_CLAIM
    }

    public RecoveryFact {
        displayText = RecoveryBounds.text(displayText, 512);
        sourceRefs = RecoveryBounds.copy(sourceRefs, 16);
        if (verificationClass == VerificationClass.REVALIDATED && sourceRefs.isEmpty())
            throw new IllegalArgumentException("Revalidation needs current sources");
    }

    @Override
    public String toString() {
        return "RecoveryFact[kind=" + kind + ", verificationClass=" + verificationClass + "]";
    }
}
