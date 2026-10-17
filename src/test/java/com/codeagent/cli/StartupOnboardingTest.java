package com.codeagent.cli;

import com.codeagent.config.*;
import com.codeagent.llm.LlmClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StartupOnboardingTest {
    @TempDir Path temp;

    ProviderConfigurationService service(Map<String, String> environment) {
        return new ProviderConfigurationService(new UserConfigStore(temp.resolve("config.json")),
                LlmClientFactory.providerDescriptors(),
                ConfigurationValues.loadEnvironmentSnapshot(environment, temp.resolve("none"), temp.resolve("none2")),
                new Properties());
    }

    @Test void validConfigurationDoesNotAskOrProbe() {
        var ui = new ProviderSetupControllerTest.Ui();
        assertEquals(0, StartupOnboarding.ensure(service(Map.of(
                "CODEAGENT_PROVIDER", "step", "STEP_API_KEY", "fixture-key")), ui, false));
        assertEquals(0, ui.secrets);
    }

    @Test void pipedMissingConfigDoesNotConsumeInput() {
        var ui = new ProviderSetupControllerTest.Ui() {
            public boolean secureInputAvailable() { return false; }
        };
        assertEquals(1, StartupOnboarding.ensure(service(Map.of()), ui, false));
        assertEquals(0, ui.secrets);
    }

    @Test void cancellationHasDifferentFirstRunAndExplicitConfigCodes() {
        var ui = new ProviderSetupControllerTest.Ui();
        ui.choices.add(-1);
        assertEquals(1, StartupOnboarding.ensure(service(Map.of()), ui, false));
        ui.choices.add(-1);
        assertEquals(0, StartupOnboarding.ensure(service(Map.of()), ui, true));
    }

    @Test void interactiveMissingConfigurationCanBeSavedAndThenLocallyValidated() {
        var ui = new ProviderSetupControllerTest.Ui();
        ui.choices.addAll(List.of(3, 1));
        ui.texts.addAll(List.of("", ""));
        assertEquals(0, StartupOnboarding.ensure(service(Map.of()), ui, false));
        assertEquals(1, ui.secrets);
        assertEquals(0, ui.checks);
    }

    @Test void environmentOverrideIsReportedAndDoesNotLoopAfterSavingAnotherDefault() throws Exception {
        var ui = new ProviderSetupControllerTest.Ui();
        ui.choices.addAll(List.of(3, 1));
        ui.texts.addAll(List.of("", ""));
        assertEquals(1, StartupOnboarding.ensure(service(Map.of("CODEAGENT_PROVIDER", "deepseek")), ui, false));
        assertEquals(1, ui.secrets);
        assertTrue(ui.choices.isEmpty());
        assertTrue(ui.output.toString().contains("当前终端环境"));
        assertTrue(ui.output.toString().contains("不会覆盖"));
        assertTrue(ui.output.toString().contains("当前有效模型配置仍无效"));
        assertTrue(Files.readString(temp.resolve("config.json")).contains("step"));
    }

    @Test void staticCommandsDoNotCreateDataRootAndNeverEchoUnknownArgument() throws Exception {
        for (String flag : List.of("--help", "--version", "--api-key")) {
            Path root = temp.resolve(flag.substring(2));
            var command = new ArrayList<>(List.of(javaExecutable(), "-Dcodeagent.home=" + root,
                    "-Duser.home=" + temp.resolve("profile"), "-Dcodeagent.provider=invalid-fixture",
                    "-cp", System.getProperty("java.class.path"), Main.class.getName(), flag));
            if (flag.equals("--api-key")) command.add("synthetic-secret");
            Process process = new ProcessBuilder(command).directory(temp.toFile()).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(flag.equals("--api-key") ? 2 : 0, process.exitValue(), output);
                assertFalse(Files.exists(root), output);
                assertFalse(output.contains("synthetic-secret"));
            } finally {
                process.destroyForcibly();
            }
        }
    }

    @Test void noTerminalConfigDoesNotWaitForUnclosedStdin() throws Exception {
        Process process = new ProcessBuilder(javaExecutable(), "-Dcodeagent.home=" + temp.resolve("root"),
                "-Duser.home=" + temp.resolve("profile"), "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "config").directory(temp.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(1, process.exitValue());
            assertFalse(Files.exists(temp.resolve("root/sessions")));
            assertFalse(Files.exists(temp.resolve("root/mcp.json")));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test void unsafeExistingAddressIsNotLocallyValid() {
        assertFalse(StartupOnboarding.valid(service(Map.of("CODEAGENT_PROVIDER", "step", "STEP_API_KEY", "fixture-key",
                "STEP_BASE_URL", "https://example.test/v1?token=fixture"))));
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}