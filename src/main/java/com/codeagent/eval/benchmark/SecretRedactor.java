package com.codeagent.eval.benchmark;

/** Compatibility entrypoint; all rules live in the neutral utility. */
public final class SecretRedactor {
    public static final String REDACTED = com.codeagent.util.SecretRedactor.REDACTED;
    private SecretRedactor() { }
    public static String redact(String input) {
        return com.codeagent.util.SecretRedactor.redact(input);
    }
}
