package com.codeagent.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Shared, non-executing configuration lookup. No process-wide dotenv cache. */
public final class ConfigurationValues {
    private ConfigurationValues() {}

    public static String get(String property, String key, String fallback) {
        return resolve(property, key, System.getProperties(), currentEnvironment(), fallback);
    }

    public static Map<String, String> currentEnvironment() {
        return loadEnvironment(System.getenv(),
                Path.of(System.getProperty("user.dir"), ".env"),
                Path.of(System.getProperty("user.home"), ".env"));
    }

    /** Empty project values shadow user dotenv values; empty process values do not override. */
    public static Map<String, String> loadEnvironment(Map<String, String> environment, Path projectEnv, Path homeEnv) {
        Map<String, String> values = new LinkedHashMap<>(readDotEnv(homeEnv));
        values.putAll(readDotEnv(projectEnv));
        if (environment != null) environment.forEach((key, value) -> {
            if (value != null && !value.isBlank()) values.put(key, value.trim());
        });
        return Map.copyOf(values);
    }

    static String resolve(String property, String key, Properties properties,
                          Map<String, String> values, String fallback) {
        String value = property == null ? null : properties.getProperty(property);
        if (value == null || value.isBlank()) value = values.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Map<String, String> readDotEnv(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        if (file == null) return values;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int separator = line.indexOf('=');
                if (separator <= 0) continue;
                String key = line.substring(0, separator).trim();
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
                values.putIfAbsent(key, line.substring(separator + 1).trim());
            }
        } catch (IOException ignored) {
            // Absent/unreadable dotenv files retain the existing fallback behavior; never echo values.
        }
        return values;
    }
}
