package com.codeagent.config;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Controls creation of new context edits, never replay of already committed edits. */
public record ContextManagementConfig(boolean enabled, String diagnostic) {
    public static final String PROPERTY_KEY = "codeagent.context.layered.enabled";
    public static final String ENVIRONMENT_KEY = "CODEAGENT_LAYERED_CONTEXT_ENABLED";

    public static ContextManagementConfig load() {
        return resolve(System.getProperties(), ConfigurationValues.currentEnvironment());
    }

    static ContextManagementConfig resolve(Properties properties, Map<String, String> environment) {
        String value = ConfigurationValues.resolve(PROPERTY_KEY, ENVIRONMENT_KEY,
                properties, environment, "false");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "1" -> new ContextManagementConfig(true, null);
            case "false", "0" -> new ContextManagementConfig(false, null);
            default -> new ContextManagementConfig(false, "Invalid layered context setting; disabled");
        };
    }
}
