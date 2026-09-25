package com.codeagent.rag.embedding;

import java.util.Objects;

public final class EmbeddingException extends Exception {
    private final String reasonCode;

    public EmbeddingException(String reasonCode, String safeMessage) {
        super(safeMessage);
        this.reasonCode = requireReasonCode(reasonCode);
    }

    public EmbeddingException(String reasonCode, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.reasonCode = requireReasonCode(reasonCode);
    }

    public String reasonCode() {
        return reasonCode;
    }

    private static String requireReasonCode(String reasonCode) {
        String value = Objects.requireNonNull(reasonCode, "reasonCode").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("reasonCode must not be blank");
        return value;
    }
}
