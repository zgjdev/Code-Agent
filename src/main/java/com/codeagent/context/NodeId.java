package com.codeagent.context;

import java.util.Objects;

/** Stable identity of a ledger message, edit replacement, or request-only insertion. */
public record NodeId(Kind kind, String scopeId, Long sourceSequence, String editId,
                     String requestAssemblyId, Integer ordinal) {
    public enum Kind { RAW, EDIT, REQUEST }

    public NodeId {
        Objects.requireNonNull(kind);
        boolean validOrdinal = ordinal != null && ordinal >= 0;
        boolean valid = switch (kind) {
            case RAW -> present(scopeId) && sourceSequence != null && sourceSequence >= 0
                    && editId == null && requestAssemblyId == null && ordinal == null;
            case EDIT -> present(scopeId) && present(editId) && validOrdinal
                    && sourceSequence == null && requestAssemblyId == null;
            case REQUEST -> present(requestAssemblyId) && validOrdinal
                    && scopeId == null && sourceSequence == null && editId == null;
        };
        if (!valid) throw new IllegalArgumentException("Invalid context node identity");
    }

    public static NodeId raw(String scopeId, long sourceSequence) {
        return new NodeId(Kind.RAW, scopeId, sourceSequence, null, null, null);
    }

    public static NodeId edit(String scopeId, String editId, int ordinal) {
        return new NodeId(Kind.EDIT, scopeId, null, editId, null, ordinal);
    }

    public static NodeId request(String requestAssemblyId, int ordinal) {
        return new NodeId(Kind.REQUEST, null, null, null, requestAssemblyId, ordinal);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
