package com.codeagent.model.recovery;

import java.util.List;

final class RecoveryBounds {

    private RecoveryBounds() {
    }

    static String text(String value, int limit) {
        if (value == null || value.codePointCount(0, value.length()) <= limit)
            return value;
        String marker = "[TRUNCATED]";
        int count = Math.max(0, limit - marker.length());
        return value.substring(0, value.offsetByCodePoints(0, count)) + marker;
    }

    static String exact(String value, int limit) {
        if (value != null && value.codePointCount(0, value.length()) > limit)
            throw new IllegalArgumentException("Recovery text exceeds bound");
        return value;
    }

    static <T> List<T> copy(List<T> values, int limit) {
        if (values == null)
            return List.of();
        if (values.size() > limit)
            throw new IllegalArgumentException("Recovery collection exceeds bound");
        return List.copyOf(values);
    }
}
