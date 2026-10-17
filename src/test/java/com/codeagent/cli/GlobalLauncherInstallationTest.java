package com.codeagent.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class GlobalLauncherInstallationTest {
    @TempDir Path temporary;

    @Test void installedCommandPreservesWorkspaceArgumentsAndConfiguration() throws Exception {
        runSmoke(temporary, false);
    }

    static void runSmoke(Path temporary, boolean distribution) throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        Path log = temporary.resolve("launcher-smoke.log");
        var command = new ArrayList<>(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", repository.resolve("src/test/resources/launcher/install-smoke.ps1").toString(),
                "-Repository", repository.toString(), "-JavaHome", System.getProperty("java.home")));
        if (distribution) command.add("-CheckPackage");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Isolated Windows launcher smoke timed out");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("PASS: install/reinstall"), output);
            if (distribution) assertTrue(output.contains("PASS: ZIP whitelist"), output);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }
}
