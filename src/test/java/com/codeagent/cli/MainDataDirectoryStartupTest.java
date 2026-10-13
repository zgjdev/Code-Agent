package com.codeagent.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MainDataDirectoryStartupTest {
    @TempDir Path temporary;

    @Test void dotenvRootIsResolvedBeforeLoggingInFreshJvm() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path profile = Files.createDirectories(temporary.resolve("profile"));
        Path data = temporary.resolve("data with spaces");
        Path legacy = temporary.resolve("legacy");
        Files.writeString(project.resolve(".env"), "CODEAGENT_HOME=" + data + "\nCODEAGENT_LOG_DIR=" + legacy);
        Process process = start(project, profile, null, LoggingProbe.class.getName());
        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue()); // Deliberately invalid provider: no remote calls or MCP startup.
        assertTrue(Files.isDirectory(data.resolve("tmp")));
        assertTrue(Files.isRegularFile(data.resolve("logs/codeagent.log")));
        assertFalse(Files.exists(profile.resolve(".codeagent")));
        assertFalse(Files.exists(legacy));
    }

    @Test void defaultRootUsesProfileWhenLaunchingFromAnotherDirectory() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path profile = Files.createDirectories(temporary.resolve("profile"));
        Process process = start(project, profile, null);
        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue());
        assertTrue(Files.isDirectory(profile.resolve(".codeagent/tmp")));
        assertTrue(Files.isDirectory(profile.resolve(".codeagent/logs")));
        assertFalse(Files.exists(project.resolve(".codeagent")));
    }

    @Test @EnabledOnOs(OS.WINDOWS) void rootRelativeHomeFailsBeforeCreatingAnyData() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path profile = Files.createDirectories(temporary.resolve("profile"));
        Process process = start(project, profile, "/Users/invalid-root-fixture");
        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue());
        assertFalse(Files.exists(profile.resolve(".codeagent")));
        assertFalse(Files.exists(project.resolve(".codeagent")));
    }

    private Process start(Path project, Path profile, String override) throws Exception {
        return start(project, profile, override, Main.class.getName());
    }

    private Process start(Path project, Path profile, String override, String entrypoint) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new java.util.ArrayList<>(java.util.List.of(javaExecutable,
                "-Duser.home=" + profile, "-Dcodeagent.provider=invalid-fixture",
                "-Dcodeagent.log.dir=" + temporary.resolve("legacy-property")));
        if (override != null) command.add("-Dcodeagent.home=" + override);
        command.addAll(java.util.List.of("-cp", System.getProperty("java.class.path"), entrypoint));
        ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile());
        builder.environment().remove("CODEAGENT_HOME");
        return builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    public static class LoggingProbe {
        public static void main(String[] args) throws Exception {
            com.codeagent.config.CodeAgentPaths.initialize();
            org.slf4j.LoggerFactory.getLogger(LoggingProbe.class).info("isolated startup fixture");
            Main.main(args);
        }
    }
}
