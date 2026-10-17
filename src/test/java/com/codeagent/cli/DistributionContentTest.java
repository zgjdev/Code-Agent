package com.codeagent.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

@EnabledOnOs(OS.WINDOWS)
class DistributionContentTest {
    @TempDir Path temporary;

    @Test void whitelistArchiveInstallsWithoutRepositoryOrEnvFiles() throws Exception {
        GlobalLauncherInstallationTest.runSmoke(temporary, true);
    }
}
