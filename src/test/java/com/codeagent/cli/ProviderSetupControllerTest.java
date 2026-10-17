package com.codeagent.cli;

import com.codeagent.config.*;
import com.codeagent.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProviderSetupControllerTest {
    @TempDir Path temp;

    ProviderConfigurationService service() {
        return service(Map.of());
    }

    ProviderConfigurationService service(Map<String, String> environment) {
        return new ProviderConfigurationService(new UserConfigStore(temp.resolve("config.json")),
                LlmClientFactory.providerDescriptors(),
                ConfigurationValues.loadEnvironmentSnapshot(environment, temp.resolve("none"), temp.resolve("none2")),
                new Properties());
    }

    static class Ui implements ProviderSetupController.Ui {
        final Deque<String> texts = new LinkedList<>();
        final Deque<Integer> choices = new ArrayDeque<>();
        final StringBuilder output = new StringBuilder();
        int secrets;
        int checks;
        char[] secret = "fixture-hidden-secret".toCharArray();

        public boolean secureInputAvailable() { return true; }
        public void message(String text) { output.append(text); }
        public int choose(String title, List<String> options) {
            output.append(title);
            return choices.remove();
        }
        public String text(String title, String current) {
            output.append(title).append(current);
            return texts.remove();
        }
        public char[] secret(String title) {
            secrets++;
            return secret.clone();
        }
        public ConnectionCheckResult check(ProviderSetupController.Probe probe) {
            checks++;
            return new ConnectionCheckResult(ConnectionCheckResult.Status.OK);
        }
    }

    @Test void savesUntestedWithoutNetworkAndWithoutSecretInUi() throws Exception {
        Ui ui = new Ui();
        ui.choices.addAll(List.of(3, 1));
        ui.texts.addAll(List.of("", ""));
        var result = new ProviderSetupController(service(), config -> {
            fail("Unexpected network check");
            return null;
        }).run(ui);

        assertEquals(ProviderSetupController.Outcome.SAVED, result);
        assertEquals(0, ui.checks);
        assertTrue(ui.output.toString().contains("尚未验证"));
        assertFalse(ui.output.toString().contains("fixture-hidden-secret"));
        assertTrue(Files.readString(temp.resolve("config.json")).contains("fixture-hidden-secret"));
    }

    @Test void targetChangedDuringCollectionIsNotOverwritten() throws Exception {
        Ui ui = concurrentWriter("step");
        assertEquals(ProviderSetupController.Outcome.FAILED, new ProviderSetupController(service()).run(ui));
        assertTrue(Files.readString(temp.resolve("config.json")).contains("concurrent-model"));
        assertTrue(ui.output.toString().contains("重新加载"));
    }

    @Test void otherProviderChangedDuringCollectionIsMerged() throws Exception {
        Ui ui = concurrentWriter("glm");
        assertEquals(ProviderSetupController.Outcome.SAVED, new ProviderSetupController(service()).run(ui));
        String saved = Files.readString(temp.resolve("config.json"));
        assertTrue(saved.contains("concurrent-model"));
        assertTrue(saved.contains("edited-model"));
    }

    private Ui concurrentWriter(String provider) {
        Ui ui = new Ui() {
            public char[] secret(String title) {
                try {
                    Files.writeString(temp.resolve("config.json"),
                            "{\"providers\":{\"" + provider + "\":{\"model\":\"concurrent-model\"}}}");
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                return super.secret(title);
            }
        };
        ui.choices.addAll(List.of(3, 1));
        ui.texts.addAll(List.of("edited-model", ""));
        return ui;
    }

    @Test void cancelDoesNotCreateConfig() {
        Ui ui = new Ui();
        ui.choices.add(-1);
        assertEquals(ProviderSetupController.Outcome.CANCELED, new ProviderSetupController(service()).run(ui));
        assertFalse(Files.exists(temp.resolve("config.json")));
    }

    @Test void canonicalSelectionDisplaysChecksAndKeepsAliasOnlyConfiguration() throws Exception {
        Path file = temp.resolve("config.json");
        String baseline = aliasConfig("fixture-alias-key", "alias-model", "https://alias.example/v1");
        Files.writeString(file, baseline);
        Ui ui = aliasUi();
        var controller = new ProviderSetupController(service(), config -> {
            assertEquals("fixture-alias-key", config.getApiKey("kimi"));
            assertEquals("alias-model", config.getModel("kimi"));
            assertEquals("https://alias.example/v1", config.getBaseUrl("kimi"));
            return successfulProbe();
        });
        assertEquals(ProviderSetupController.Outcome.SAVED, controller.run(ui));
        assertTrue(ui.output.toString().contains("模型=用户配置，地址=用户配置，密钥=已配置（用户配置）"));
        assertTrue(ui.output.toString().contains("模型（回车保留）alias-model"));
        assertTrue(ui.output.toString().contains("服务地址（回车保留）https://alias.example/v1"));
        assertFalse(ui.output.toString().contains("fixture-alias-key"));
        var saved = new UserConfigStore(file).readSnapshot().document();
        assertEquals("moonshot", saved.path("defaultProvider").asText());
        assertFalse(saved.path("providers").path("kimi").has("apiKey"));
        assertEquals("fixture-alias-key", service().temporaryConfig(service().newDraft("kimi")).getApiKey("kimi"));
        assertTrue(ui.output.toString().endsWith("本次短请求验证成功。"));
    }

    @Test void aliasChangedAfterSuccessfulCheckCannotBeSavedAsVerified() throws Exception {
        Path file = temp.resolve("config.json");
        for (String concurrent : List.of(
                aliasConfig("changed-key", "alias-model", "https://alias.example/v1"),
                aliasConfig("fixture-alias-key", "changed-model", "https://alias.example/v1"),
                aliasConfig("fixture-alias-key", "alias-model", "https://changed.example/v1"))) {
            Files.writeString(file, aliasConfig("fixture-alias-key", "alias-model", "https://alias.example/v1"));
            Ui ui = aliasUi();
            var controller = new ProviderSetupController(service(), config -> {
                try { Files.writeString(file, concurrent); }
                catch (Exception failure) { throw new AssertionError(failure); }
                return successfulProbe();
            });
            assertEquals(ProviderSetupController.Outcome.FAILED, controller.run(ui));
            assertEquals(concurrent, Files.readString(file));
            assertTrue(ui.output.toString().contains("重新加载"));
            assertFalse(ui.output.toString().contains("配置已保存"));
            assertFalse(ui.output.toString().contains("本次短请求验证成功"));
        }
    }

    private Ui aliasUi() {
        Ui ui = new Ui() {
            @Override public ConnectionCheckResult check(ProviderSetupController.Probe probe) { return probe.execute(); }
        };
        ui.secret = new char[0];
        ui.choices.addAll(List.of(4, 0, 1));
        ui.texts.addAll(List.of("", ""));
        return ui;
    }

    private static String aliasConfig(String key, String model, String endpoint) {
        return "{\"defaultProvider\":\"moonshot\",\"providers\":{\"moonshot\":{\"apiKey\":\"" + key
                + "\",\"model\":\"" + model + "\",\"baseUrl\":\"" + endpoint + "\"}}}";
    }

    private static ProviderSetupController.Probe successfulProbe() {
        return new ProviderSetupController.Probe() {
            public ConnectionCheckResult execute() { return new ConnectionCheckResult(ConnectionCheckResult.Status.OK); }
            public void cancel() { }
        };
    }

    @Test void malformedConfigIsPreservedWithoutPrompt() throws Exception {
        Files.writeString(temp.resolve("config.json"), "{broken-secret");
        Ui ui = new Ui();
        assertEquals(ProviderSetupController.Outcome.FAILED, new ProviderSetupController(service()).run(ui));
        assertEquals("{broken-secret", Files.readString(temp.resolve("config.json")));
        assertFalse(ui.output.toString().contains("broken-secret"));
    }

    @Test void probeRequiresExplicitChoiceAndSuccessfulProbeStateIsLostOnEdit() {
        Ui ui = new Ui();
        ui.choices.addAll(List.of(3, 0, 2, 3, 1));
        ui.texts.addAll(List.of("", "", "changed-model", ""));
        var controller = new ProviderSetupController(service(), config -> new ProviderSetupController.Probe() {
            public ConnectionCheckResult execute() { return new ConnectionCheckResult(ConnectionCheckResult.Status.OK); }
            public void cancel() { }
        });
        assertEquals(ProviderSetupController.Outcome.SAVED, controller.run(ui));
        assertEquals(1, ui.checks);
        assertTrue(ui.output.toString().contains("可能产生少量调用费用"));
        assertTrue(ui.output.toString().endsWith("配置已保存，下次启动自动使用；尚未验证。"));
    }

    @Test void unsafeExistingAddressIsNeverDisplayedAndMustBeReplaced() {
        Ui ui = new Ui();
        ui.choices.addAll(List.of(3, 1));
        ui.texts.addAll(List.of("", "https://safe.example/v1"));
        var result = new ProviderSetupController(service(Map.of(
                "STEP_BASE_URL", "https://example.test/v1?token=fixture-in-url"))).run(ui);
        assertEquals(ProviderSetupController.Outcome.SAVED, result);
        assertFalse(ui.output.toString().contains("fixture-in-url"));
        assertTrue(ui.output.toString().contains("safe.example"));
    }

    @Test void keepingEnvironmentKeyDoesNotCopyItIntoSavedJson() throws Exception {
        Ui ui = new Ui();
        ui.secret = new char[0];
        ui.choices.addAll(List.of(3, 1));
        ui.texts.addAll(List.of("", ""));
        var result = new ProviderSetupController(service(Map.of("STEP_API_KEY", "fixture-environment-key"))).run(ui);
        assertEquals(ProviderSetupController.Outcome.SAVED, result);
        assertFalse(Files.readString(temp.resolve("config.json")).contains("fixture-environment-key"));
        assertFalse(Files.readString(temp.resolve("config.json")).contains("apiKey"));
    }
}
