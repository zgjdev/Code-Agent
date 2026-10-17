package com.codeagent.config;

import java.util.Arrays;
import java.util.Objects;

/** Explicit keep/replace intent. This type deliberately retains identity equals/toString semantics. */
public final class SecretUpdate implements AutoCloseable {
    public enum Action { KEEP, REPLACE }
    private final Action action;
    private char[] value;
    private boolean closed;
    private SecretUpdate(Action action, char[] value) { this.action = action; this.value = value; }
    public static SecretUpdate keep() { return new SecretUpdate(Action.KEEP, null); }
    public static SecretUpdate replace(char[] value) {
        Objects.requireNonNull(value);
        if (!ApiKeyValidation.isHeaderSafe(new String(value))) throw new IllegalArgumentException("Invalid API credential format");
        return new SecretUpdate(Action.REPLACE, value.clone());
    }
    public Action action() { return action; }
    String replacement() {
        if (closed) throw new IllegalStateException("Secret draft is closed");
        return value == null ? null : new String(value);
    }
    @Override public void close() { if (value != null) Arrays.fill(value, '\0'); value = null; closed = true; }
    @Override public String toString() { return "SecretUpdate[" + action + "]"; }
}
