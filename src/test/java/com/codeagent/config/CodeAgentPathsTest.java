package com.codeagent.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class CodeAgentPathsTest {
    @TempDir Path temporary;
    private Properties saved;

    @BeforeEach void isolate() {
        saved = (Properties) System.getProperties().clone();
        System.setProperty("codeagent.home", temporary.resolve("data").toString());
    }

    @AfterEach void restore() { System.setProperties(saved); }

    @Test void defaultRootIsIndependentOfWorkingDirectory() {
        assertEquals(temporary.resolve(".codeagent"), CodeAgentPaths.resolveRoot(null, temporary));
        System.setProperty("user.dir", temporary.resolve("different-drive-workspace").toString());
        assertEquals(temporary.resolve("data"), CodeAgentPaths.root());
        assertFalse(Files.exists(CodeAgentPaths.root()));
    }

    @Test void homeExpansionAndSpacesAreSupported() {
        assertEquals(temporary.resolve("my data"), CodeAgentPaths.resolveRoot("~/my data", temporary));
        assertEquals(temporary.resolve("my data"), CodeAgentPaths.resolveRoot("~\\my data", temporary));
    }

    @Test void relativeRootAndEscapingChildAreRejectedWithoutCreation() {
        assertThrows(IllegalArgumentException.class, () -> CodeAgentPaths.resolveRoot("relative", temporary));
        assertThrows(IllegalArgumentException.class, () -> CodeAgentPaths.resolveRoot(temporary + "/data\nrule", temporary));
        assertThrows(IllegalArgumentException.class, () -> CodeAgentPaths.resolve("../escape"));
        assertThrows(IllegalArgumentException.class, () -> CodeAgentPaths.resolve(temporary.toString()));
        assertFalse(Files.exists(temporary.resolve("data")));
    }

    @Test @EnabledOnOs(OS.WINDOWS) void windowsRootRelativeAndDriveRelativePathsAreRejected() {
        for (String value : new String[]{"/Users/yourname", "/tmp", "\\tmp", "D:tmp"}) {
            assertThrows(IllegalArgumentException.class, () -> CodeAgentPaths.resolveRoot(value, temporary));
        }
        assertEquals(Path.of("D:/data"), CodeAgentPaths.resolveRoot("D:/data", temporary));
    }

    @Test @EnabledOnOs({OS.MAC, OS.LINUX}) void posixAbsoluteRootIsAccepted() {
        assertEquals(Path.of("/Users/person/.codeagent"),
                CodeAgentPaths.resolveRoot("/Users/person/.codeagent", temporary));
    }

    @Test void initializePinsRootAndRedirectsJvmSqliteAndLogging() throws Exception {
        System.setProperty("codeagent.log.dir", "/Users/yourname/old-logs");
        CodeAgentPaths.initialize();
        Path root = temporary.resolve("data");
        assertEquals(root.resolve("logs").toString(), System.getProperty("codeagent.log.dir"));
        assertEquals(root.resolve("tmp").toString(), System.getProperty("java.io.tmpdir"));
        assertEquals(root.resolve("tmp").toString(), System.getProperty("org.sqlite.tmpdir"));
        assertTrue(Files.isDirectory(root.resolve("tmp")));
    }

    @Test void processCachesFollowRootWithoutChangingIdentity() throws Exception {
        Map<String, String> environment = new HashMap<>(Map.of(
                "Temp", "legacy", "NPM_CONFIG_CACHE", "legacy", "HOME", "original-home"));
        CodeAgentPaths.configureProcessEnvironment(environment);
        for (String key : new String[]{"TMP", "TEMP", "TMPDIR"}) {
            assertEquals(temporary.resolve("data/tmp").toString(), environment.get(key));
        }
        assertEquals(temporary.resolve("data/cache/npm").toString(), environment.get("npm_config_cache"));
        assertEquals("original-home", environment.get("HOME"));
        assertEquals(temporary.resolve("data").toString(), environment.get("CODEAGENT_HOME"));
        assertFalse(environment.containsKey("Temp"));
        assertFalse(environment.containsKey("NPM_CONFIG_CACHE"));
    }

    @Test void reportsAreSeparatedByWorkspace() throws Exception {
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path second = Files.createDirectory(temporary.resolve("second"));
        assertTrue(CodeAgentPaths.workspaceReports(first).startsWith(CodeAgentPaths.resolve("reports/better-harness")));
        assertNotEquals(CodeAgentPaths.workspaceReports(first), CodeAgentPaths.workspaceReports(second));
        assertEquals(CodeAgentPaths.workspaceReports(first), CodeAgentPaths.workspaceReports(first.resolve(".")));
    }
}
