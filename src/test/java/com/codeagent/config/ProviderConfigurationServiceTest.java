package com.codeagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderConfigurationServiceTest {
    @Test void invalidHeaderSecretsAreNotStoredOrConsideredUsable() throws Exception {
        for (String secret : List.of("fixture\nprivate", "fixture\tprivate", "fixture\u00e9private")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> SecretUpdate.replace(secret.toCharArray()));
            assertFalse(failure.toString().contains("fixture"));
        }
        Path file = directory.resolve("config.json");
        Files.writeString(file, "{\"defaultProvider\":\"step\",\"providers\":{\"step\":{\"apiKey\":\"fixture\\nprivate\"}}}");
        var service = new ProviderConfigurationService(new UserConfigStore(file), descriptors(),
                ConfigurationValues.loadEnvironmentSnapshot(Map.of(), null, null), new Properties());
        assertFalse(service.resolve().apiKeyConfigured());
    }
    @TempDir Path directory;
    private List<ProviderDescriptor> descriptors() {
        return List.of(new ProviderDescriptor("glm", "GLM", "default-model", "https://fixed.example/v1",
                ProviderDescriptor.EndpointPolicy.FIXED, true, false),
                new ProviderDescriptor("step", "Step", "step-default", "https://editable.example/v1",
                        ProviderDescriptor.EndpointPolicy.EDITABLE, true, false));
    }
    @Test void resolvesEachSourceAndOnlyPersistsUserEdits() throws Exception {
        Path project = directory.resolve("project.env"), user = directory.resolve("user.env"), file = directory.resolve("config.json");
        Files.writeString(user, "STEP_MODEL=user-model\nSTEP_API_KEY=user-fixture\n");
        Files.writeString(project, "STEP_API_KEY=environment-private-fixture\n");
        Files.writeString(file, "{\"providers\":{\"step\":{\"model\":\"json-model\"}}}");
        var environment = ConfigurationValues.loadEnvironmentSnapshot(Map.of("CODEAGENT_PROVIDER", "step"), project, user);
        var service = new ProviderConfigurationService(new UserConfigStore(file), descriptors(), environment, new Properties());
        var resolved = service.resolve();
        assertEquals("step", resolved.provider());
        assertEquals(SettingSource.PROCESS_ENV, resolved.providerSource());
        assertEquals(SettingSource.USER_CONFIG, resolved.modelSource());
        assertEquals(SettingSource.PROJECT_ENV, resolved.apiKeySource());
        assertTrue(resolved.apiKeyConfigured());
        assertFalse(resolved.toString().contains("environment-private-fixture"));
        try (var draft = service.newDraft("step")) { service.save(draft); }
        assertFalse(Files.readString(file).contains("environment-private-fixture"));
        assertFalse(Files.readString(file).contains("defaultProvider"));
    }

    @Test void temporaryConfigTestsChosenDraftWithoutSelectorOrSecretFallback() throws Exception {
        var environment = ConfigurationValues.loadEnvironmentSnapshot(Map.of("CODEAGENT_PROVIDER", "glm", "GLM_API_KEY", "other-key"), null, null);
        var service = new ProviderConfigurationService(new UserConfigStore(directory.resolve("config.json")), descriptors(), environment, new Properties());
        try (var draft = new ProviderSettingsDraft(service.newDraft("step").baseline(), "step", "chosen-model",
                "https://chosen.example/v1", null, SecretUpdate.replace("chosen-key".toCharArray()), true)) {
            var temporary = service.temporaryConfig(draft);
            assertEquals("step", temporary.getDefaultProvider());
            assertEquals("chosen-model", temporary.getModel("step"));
            assertTrue("chosen-key".equals(temporary.getApiKey("step")));
            assertEquals("https://chosen.example/v1", temporary.getBaseUrl("step"));
        }
        assertFalse(Files.exists(directory.resolve("config.json")));
    }

    @Test void validatesFixedEndpointsAndBlankInputsAndJavaPropertySelection() {
        Properties properties = new Properties(); properties.setProperty("codeagent.provider", "step");
        var service = new ProviderConfigurationService(new UserConfigStore(directory.resolve("config.json")), descriptors(),
                ConfigurationValues.loadEnvironmentSnapshot(Map.of(), null, null), properties);
        assertEquals(SettingSource.JAVA_PROPERTY, service.resolve().providerSource());
        try (var draft = new ProviderSettingsDraft(service.newDraft("glm").baseline(), "glm", null,
                "https://unused.example", null, SecretUpdate.keep(), true)) {
            assertThrows(IllegalArgumentException.class, () -> service.save(draft));
        }
        try (var draft = new ProviderSettingsDraft(service.newDraft("step").baseline(), "step", " ", null, null, SecretUpdate.keep(), true)) {
            assertThrows(IllegalArgumentException.class, () -> service.save(draft));
        }
        assertThrows(IllegalArgumentException.class, () -> service.newDraft("custom"));
    }

    @Test void blankCanonicalValueMasksLegacyAliasAndShowsProjectSource() throws Exception {
        Path project = directory.resolve("project.env");
        Files.writeString(project, "KIMI_API_KEY=\nMOONSHOT_API_KEY=alias-fixture\n");
        var descriptor = new ProviderDescriptor("kimi", "Kimi", "model", "https://fixture.example", ProviderDescriptor.EndpointPolicy.EDITABLE, true, false);
        var service = new ProviderConfigurationService(new UserConfigStore(directory.resolve("config.json")), List.of(descriptor),
                ConfigurationValues.loadEnvironmentSnapshot(Map.of("CODEAGENT_PROVIDER", "kimi"), project, null), new Properties());
        assertFalse(service.resolve().apiKeyConfigured());
        assertEquals(SettingSource.PROJECT_ENV, service.resolve().apiKeySource());
    }

    @Test void existingProviderAliasesKeepTheirExplicitJsonConfiguration() throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, "{\"defaultProvider\":\"moonshot\",\"providers\":{\"moonshot\":{\"apiKey\":\"alias-json-fixture\",\"model\":\"alias-model\"}}}");
        var kimi = new ProviderDescriptor("kimi", "Kimi", "default-model", "https://fixture.example", ProviderDescriptor.EndpointPolicy.EDITABLE, true, false);
        var service = new ProviderConfigurationService(new UserConfigStore(file), List.of(kimi),
                ConfigurationValues.loadEnvironmentSnapshot(Map.of("KIMI_API_KEY", ""), null, null), new Properties());
        var resolved = service.resolve();
        assertEquals("kimi", resolved.provider());
        assertTrue(resolved.apiKeyConfigured());
        assertEquals(SettingSource.USER_CONFIG, resolved.apiKeySource());
        assertEquals("alias-model", resolved.model());
        assertTrue(service.resolve("moonshot").apiKeyConfigured());
        var canonical = service.resolve(service.readSnapshot(), "kimi");
        assertTrue(canonical.apiKeyConfigured());
        assertEquals("alias-model", canonical.model());
        assertEquals(SettingSource.USER_CONFIG, canonical.apiKeySource());
        try (var draft = service.newDraft("moonshot")) {
            assertTrue("alias-json-fixture".equals(service.temporaryConfig(draft).getApiKey("kimi")));
        }
        try (var draft = new ProviderSettingsDraft(service.readSnapshot(), "kimi", "edited-model", null, null, SecretUpdate.keep(), true)) {
            service.save(draft);
        }
        // Keeping an alias-only JSON key must not make the next launch lose its selected alias fallback.
        assertEquals("moonshot", CodeAgentConfig.load(file, Map.of()).getDefaultProvider());
        assertTrue(service.resolve().apiKeyConfigured());
        assertEquals("edited-model", service.resolve().model());
        assertEquals("step", ProviderDescriptor.normalizeId(" step-fun "));
        assertEquals("hunyuan", ProviderDescriptor.normalizeId("tokenhub"));
        assertEquals("xfyun", ProviderDescriptor.normalizeId("iflytek-maas"));
    }

    @Test void aliasDraftTestsAndSavesTheSameCanonicalProvider() throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, "{\"defaultProvider\":\"kimi\",\"providers\":{\"kimi\":{\"apiKey\":\"old-fixture\"}}}");
        var kimi = new ProviderDescriptor("kimi", "Kimi", "model", "https://fixture.example",
                ProviderDescriptor.EndpointPolicy.EDITABLE, true, false);
        var service = new ProviderConfigurationService(new UserConfigStore(file), List.of(kimi),
                ConfigurationValues.loadEnvironmentSnapshot(Map.of(), null, null), new Properties());
        try (var draft = new ProviderSettingsDraft(service.readSnapshot(), "moonshot", null, null, null,
                SecretUpdate.replace("replacement-fixture".toCharArray()), true)) {
            assertEquals("replacement-fixture", service.temporaryConfig(draft).getApiKey("kimi"));
            service.save(draft);
        }
        assertEquals("replacement-fixture", CodeAgentConfig.load(file, Map.of()).getApiKey("kimi"));
    }

    @Test void propertyAndEnvironmentAliasSelectionAlsoProtectSavedDependencies() throws Exception {
        Path file = directory.resolve("config.json");
        String baseline = "{\"defaultProvider\":\"glm\",\"providers\":{\"moonshot\":{\"apiKey\":\"alias-fixture\",\"model\":\"alias-model\"}}}";
        var kimi = new ProviderDescriptor("kimi", "Kimi", "default-model", "https://fixture.example",
                ProviderDescriptor.EndpointPolicy.EDITABLE, true, false);
        for (boolean property : List.of(false, true)) {
            Files.writeString(file, baseline);
            var properties = new Properties();
            if (property) properties.setProperty("codeagent.provider", "moonshot");
            var service = new ProviderConfigurationService(new UserConfigStore(file), List.of(kimi),
                    ConfigurationValues.loadEnvironmentSnapshot(property ? Map.of() : Map.of("CODEAGENT_PROVIDER", "moonshot"), null, null), properties);
            try (var draft = service.newDraft("kimi")) {
                assertEquals("alias-model", service.resolve(draft.baseline(), "kimi").model());
                assertTrue(service.resolve(draft.baseline(), "kimi").apiKeyConfigured());
                assertEquals("alias-fixture", service.temporaryConfig(draft).getApiKey("kimi"));
                String concurrent = baseline.replace("alias-fixture", "changed-fixture");
                Files.writeString(file, concurrent);
                assertEquals(UserConfigStore.Failure.CONFLICT, assertThrows(UserConfigStore.ConfigStoreException.class,
                        () -> service.save(draft)).reason());
                assertEquals(concurrent, Files.readString(file));
            }
        }
    }

    @Test void unsafeEndpointsCannotBeSavedEvenWithoutConnectionTest() {
        var service = new ProviderConfigurationService(new UserConfigStore(directory.resolve("config.json")), descriptors(),
                ConfigurationValues.loadEnvironmentSnapshot(Map.of(), null, null), new Properties());
        for (String endpoint : List.of("http://remote.example/v1", "https://remote.example/v1?key=fixture",
                "https://user:fixture@remote.example/v1", "https://remote.example/v1#fixture")) {
            try (var draft = new ProviderSettingsDraft(service.readSnapshot(), "step", null, endpoint, null, SecretUpdate.keep(), true)) {
                assertThrows(IllegalArgumentException.class, () -> service.save(draft));
            }
        }
        assertFalse(Files.exists(directory.resolve("config.json")));
    }

    @Test void fixedEndpointDisplayAndTemporaryConfigFollowTheSelectedModel() throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, "{\"providers\":{\"glm\":{\"model\":\"glm-5v-turbo\",\"baseUrl\":\"https://ignored.example\"}}}");
        var prefixes = new HashMap<String, String>();
        prefixes.put("glm-5", "https://generic.example/v1");
        prefixes.put("glm-5v", "https://vision.example/v1");
        var glm = new ProviderDescriptor("glm", "GLM", "glm-default", "https://coding.example/v1",
                ProviderDescriptor.EndpointPolicy.FIXED, true, false, prefixes);
        prefixes.clear(); // descriptors must defensively copy the metadata
        assertEquals("https://vision.example/v1", glm.endpointForModel(" GLM-5V-TURBO "));
        assertEquals("https://coding.example/v1", glm.endpointForModel(null));
        var service = new ProviderConfigurationService(new UserConfigStore(file), List.of(glm),
                ConfigurationValues.loadEnvironmentSnapshot(Map.of(), null, null), new Properties());
        assertEquals("https://vision.example/v1", service.resolve().baseUrl());
        try (var draft = service.newDraft("glm")) {
            assertEquals("https://vision.example/v1", service.temporaryConfig(draft).getBaseUrl("glm"));
        }
        try (var draft = new ProviderSettingsDraft(service.readSnapshot(), "glm", "glm-default", null, null, SecretUpdate.keep(), false)) {
            assertEquals("https://coding.example/v1", service.temporaryConfig(draft).getBaseUrl("glm"));
        }
    }
}
