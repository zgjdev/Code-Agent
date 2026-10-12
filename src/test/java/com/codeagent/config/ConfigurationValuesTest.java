package com.codeagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationValuesTest {
    @TempDir Path directory;

    @Test void propertyThenProcessThenProjectThenHomeThenDefault() throws Exception {
        Path project = directory.resolve("project.env");
        Path home = directory.resolve("home.env");
        Files.writeString(home, "SETTING=home\nHOME_ONLY=home\n");
        Files.writeString(project, "SETTING=project\n");
        var values = ConfigurationValues.loadEnvironment(Map.of("SETTING", "process"), project, home);
        var properties = new Properties();
        properties.setProperty("setting", "property");
        assertEquals("property", ConfigurationValues.resolve("setting", "SETTING", properties, values, "default"));
        properties.clear();
        assertEquals("process", ConfigurationValues.resolve("setting", "SETTING", properties, values, "default"));
        values = ConfigurationValues.loadEnvironment(Map.of(), project, home);
        assertEquals("project", ConfigurationValues.resolve(null, "SETTING", properties, values, "default"));
        assertEquals("home", ConfigurationValues.resolve(null, "HOME_ONLY", properties, values, "default"));
        assertEquals("default", ConfigurationValues.resolve(null, "ABSENT", properties, values, "default"));
    }

    @Test void emptyProjectSecretMasksHomeSecretWithoutEchoingEither() throws Exception {
        Path project = directory.resolve("project.env");
        Path home = directory.resolve("home.env");
        Files.writeString(home, "KEY=home-fixture\n");
        Files.writeString(project, "KEY=\n");
        var values = ConfigurationValues.loadEnvironment(Map.of("KEY", ""), project, home);
        assertTrue(ConfigurationValues.resolve(null, "KEY", new Properties(), values, null) == null);
    }

    @Test void parsesUtf8AndPreservesEqualsAndHashInValues() throws Exception {
        Path file = directory.resolve("project.env");
        Files.writeString(file, "# ignored=comment\nPATH_VALUE=目录/模型\nVALUE=a=b#literal\nVALUE=second\nINVALID LINE\nexport BAD=value\n");
        var values = ConfigurationValues.loadEnvironment(Map.of(), file, null);
        assertEquals("目录/模型", values.get("PATH_VALUE"));
        assertEquals("a=b#literal", values.get("VALUE"));
        assertFalse(values.containsKey("BAD"));
        assertFalse(values.containsKey("ignored"));
    }

    @Test void missingFilesAndBlankPropertiesKeepDefaults() {
        var values = ConfigurationValues.loadEnvironment(Map.of(), directory.resolve("missing.env"), null);
        var properties = new Properties();
        properties.setProperty("setting", " ");
        assertEquals("default", ConfigurationValues.resolve("setting", "SETTING", properties, values, "default"));
    }

    @Test void embeddingLoadsDotEnvWithoutOverwritingExplicitJson() throws Exception {
        Path env = directory.resolve("project.env");
        Path json = directory.resolve("config.json");
        Files.writeString(env, "EMBEDDING_MODE=remote\nEMBEDDING_PROVIDER=jina\nEMBEDDING_MODEL=fixture-model\nEMBEDDING_LOCAL_MODEL_DIR=模型\n");
        var values = ConfigurationValues.loadEnvironment(Map.of(), env, null);
        assertEquals("remote", CodeAgentConfig.load(json, values).getEmbedding().getMode());
        assertEquals("模型", CodeAgentConfig.load(json, values).getEmbedding().getLocalModelDirectory());
        Files.writeString(json, "{\"embedding\":{\"mode\":\"local\",\"provider\":\"bge\"}}");
        var embedding = CodeAgentConfig.load(json, values).getEmbedding();
        assertEquals("local", embedding.getMode());
        assertEquals("bge", embedding.getProvider());
        assertEquals("fixture-model", embedding.getModel());
    }
}
