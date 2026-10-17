package com.codeagent.llm;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.config.ProviderDescriptor;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LlmClientOptionsTest {
    @Test void glmModelEndpointMetadataMatchesItsExistingRouting() {
        var descriptor = LlmClientFactory.providerDescriptors().stream().filter(d -> d.id().equals("glm")).findFirst().orElseThrow();
        for (String model : List.of("glm-5v-turbo", " GLM-5V-TURBO ", "glm-5.1", "glm-5.3")) {
            assertEquals(new GLMClient("fixture", model).getApiUrl(), descriptor.endpointForModel(model) + "/chat/completions");
        }
        assertThrows(UnsupportedOperationException.class, () -> descriptor.modelEndpointPrefixes().clear());
    }

    @Test void catalogIsImmutableAndMatchesAllActualClientDefaults() {
        var descriptors = LlmClientFactory.providerDescriptors();
        assertEquals(List.of("glm", "deepseek", "hunyuan", "step", "kimi", "freellmapi", "xfyun", "agnes"),
                descriptors.stream().map(ProviderDescriptor::id).toList());
        assertThrows(UnsupportedOperationException.class, descriptors::clear);
        var config = new CodeAgentConfig() {
            @Override public String getApiKey(String provider) { return "fixture-only"; }
            @Override public String getModel(String provider) { return null; }
            @Override public String getBaseUrl(String provider) { return null; }
        };
        for (var descriptor : descriptors) {
            var client = (AbstractOpenAiCompatibleClient) LlmClientFactory.create(descriptor.id(), config);
            assertEquals(descriptor.defaultModel(), client.getModelName());
            assertEquals(descriptor.defaultBaseUrl() + "/chat/completions", client.getApiUrl());
            assertEquals(descriptor.id().equals("glm") || descriptor.id().equals("deepseek"),
                    descriptor.endpointPolicy() == ProviderDescriptor.EndpointPolicy.FIXED);
        }
    }

    @Test void instanceOptionsDoNotChangeDefaultTransportOrRetry() {
        var config = new CodeAgentConfig() {
            @Override public String getApiKey(String provider) { return "fixture-only"; }
        };
        var shared = AbstractOpenAiCompatibleClient.SHARED_HTTP_CLIENT;
        for (var descriptor : LlmClientFactory.providerDescriptors()) {
            var client = (AbstractOpenAiCompatibleClient) LlmClientFactory.create(descriptor.id(), config,
                    LlmClientOptions.CONFIGURATION_CHECK);
            assertEquals(5000, client.httpClient().connectTimeoutMillis());
            assertEquals(15000, client.httpClient().callTimeoutMillis());
            assertFalse(client.httpClient().followRedirects());
            assertFalse(client.httpClient().followSslRedirects());
            assertFalse(client.httpClient().retryOnConnectionFailure());
            assertEquals(1, client.retryPolicy().maxAttempts());
        }
        assertTrue(shared.followRedirects());
        assertSame(shared, new StepClient("fixture-only").httpClient());
        assertThrows(IllegalArgumentException.class,
                () -> new LlmClientOptions(Duration.ZERO, Duration.ofSeconds(1), 1, true));
    }
}
