package com.codeagent.config;

/** Validate header representability without including credentials in diagnostics. */
public final class ApiKeyValidation {
    private ApiKeyValidation() { }

    public static boolean isHeaderSafe(String value) {
        return value != null && !value.isBlank() && value.chars().allMatch(c -> c >= 0x20 && c <= 0x7e);
    }
}
