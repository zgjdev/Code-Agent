package com.codeagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagementConfigTest {
    @TempDir Path directory;

    @Test void developmentDefaultIsDisabledAndHasNoDiagnostic() {
        var config = ContextManagementConfig.resolve(new Properties(), Map.of());
        assertFalse(config.enabled());
        assertNull(config.diagnostic());
    }

    @Test void acceptsExplicitBooleanAndNumericValues() {
        for (String value : new String[]{"true", "TRUE", "1", " true "}) {
            assertTrue(ContextManagementConfig.resolve(new Properties(),
                    Map.of(ContextManagementConfig.ENVIRONMENT_KEY, value)).enabled());
        }
        for (String value : new String[]{"false", "FALSE", "0", " false "}) {
            var config = ContextManagementConfig.resolve(new Properties(),
                    Map.of(ContextManagementConfig.ENVIRONMENT_KEY, value));
            assertFalse(config.enabled());
            assertNull(config.diagnostic());
        }
    }

    @Test void invalidPropertyFailsClosedWithoutExposingValueOrFallingBack() {
        Properties properties = new Properties();
        properties.setProperty(ContextManagementConfig.PROPERTY_KEY, "sensitive-invalid-fixture");
        var config = ContextManagementConfig.resolve(properties,
                Map.of(ContextManagementConfig.ENVIRONMENT_KEY, "true"));
        assertFalse(config.enabled());
        assertEquals("Invalid layered context setting; disabled", config.diagnostic());
        assertFalse(config.toString().contains("sensitive-invalid-fixture"));
    }

    @Test void usesUnifiedPropertyProcessProjectAndUserPrecedence() throws Exception {
        Path project = directory.resolve("project.env");
        Path user = directory.resolve("user.env");
        Files.writeString(user, ContextManagementConfig.ENVIRONMENT_KEY + "=true\n");
        Files.writeString(project, ContextManagementConfig.ENVIRONMENT_KEY + "=false\n");
        Properties properties = new Properties();
        assertTrue(ContextManagementConfig.resolve(properties,
                ConfigurationValues.loadEnvironment(Map.of(), null, user)).enabled());
        assertFalse(ContextManagementConfig.resolve(properties,
                ConfigurationValues.loadEnvironment(Map.of(), project, user)).enabled());
        var values = ConfigurationValues.loadEnvironment(
                Map.of(ContextManagementConfig.ENVIRONMENT_KEY, "true"), project, user);
        assertTrue(ContextManagementConfig.resolve(properties, values).enabled());
        properties.setProperty(ContextManagementConfig.PROPERTY_KEY, "0");
        assertFalse(ContextManagementConfig.resolve(properties, values).enabled());
    }
}
