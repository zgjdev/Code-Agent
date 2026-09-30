package com.codeagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputRequestTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void providerClassesDeclareOnlyVerifiedCapabilities() {
        assertEquals(StructuredOutputCapability.JSON_SCHEMA,
                new HunyuanClient("test-key").structuredOutputCapability());
        assertEquals(StructuredOutputCapability.JSON_OBJECT,
                new DeepSeekClient("test-key").structuredOutputCapability());
        assertEquals(StructuredOutputCapability.JSON_OBJECT,
                new StepClient("test-key").structuredOutputCapability());
        assertEquals(StructuredOutputCapability.NONE,
                new GLMClient("test-key").structuredOutputCapability());
        assertEquals(StructuredOutputCapability.NONE,
                new KimiClient("test-key").structuredOutputCapability());
    }

    @Test
    void jsonObjectCapabilitySendsNativeResponseFormat() throws Exception {
        server.enqueue(success());
        TestClient client = client(StructuredOutputCapability.JSON_OBJECT);

        client.chatStructured(messages(), null, spec(), LlmClient.StreamListener.NO_OP);

        JsonNode body = requestBody(server.takeRequest(1, TimeUnit.SECONDS));
        assertEquals("json_object", body.path("response_format").path("type").asText());
    }

    @Test
    void jsonSchemaCapabilitySendsNameStrictAndSchema() throws Exception {
        server.enqueue(success());
        TestClient client = client(StructuredOutputCapability.JSON_SCHEMA);

        client.chatStructured(messages(), null, spec(), LlmClient.StreamListener.NO_OP);

        JsonNode body = requestBody(server.takeRequest(1, TimeUnit.SECONDS));
        JsonNode format = body.path("response_format");
        assertEquals("json_schema", format.path("type").asText());
        assertEquals("sample_contract", format.path("json_schema").path("name").asText());
        assertTrue(format.path("json_schema").path("strict").asBoolean());
        assertEquals("object", format.path("json_schema").path("schema").path("type").asText());
    }

    @Test
    void noneCapabilityDoesNotSendResponseFormat() throws Exception {
        server.enqueue(success());
        TestClient client = client(StructuredOutputCapability.NONE);

        client.chatStructured(messages(), null, spec(), LlmClient.StreamListener.NO_OP);

        JsonNode body = requestBody(server.takeRequest(1, TimeUnit.SECONDS));
        assertFalse(body.has("response_format"));
    }

    @Test
    void schemaValidationErrorDoesNotMasqueradeAsUnsupportedCapability() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"invalid json_schema: required field missing\"}}"));
        TestClient client = client(StructuredOutputCapability.JSON_SCHEMA);

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> client.chatStructured(messages(), null, spec(), LlmClient.StreamListener.NO_OP));

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void explicitUnsupportedResponseFormatFallsBackToPlainChat() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"unsupported parameter response_format\"}}"));
        server.enqueue(success());
        TestClient client = client(StructuredOutputCapability.JSON_SCHEMA);

        LlmClient.ChatResponse response = client.chatStructured(
                messages(), null, spec(), LlmClient.StreamListener.NO_OP);

        assertEquals("{\"ok\":true}", response.content());
        JsonNode first = requestBody(server.takeRequest(1, TimeUnit.SECONDS));
        JsonNode second = requestBody(server.takeRequest(1, TimeUnit.SECONDS));
        assertTrue(first.has("response_format"));
        assertFalse(second.has("response_format"));
    }

    private TestClient client(StructuredOutputCapability capability) {
        return new TestClient(server.url("/v1/chat/completions").toString(), capability);
    }

    private static List<LlmClient.Message> messages() {
        return List.of(
                LlmClient.Message.system("Return JSON only."),
                LlmClient.Message.user("Return an object."));
    }

    private static StructuredOutputSpec spec() {
        return new StructuredOutputSpec(
                "sample_contract",
                JSON.createObjectNode()
                        .put("type", "object")
                        .set("properties", JSON.createObjectNode()
                                .set("ok", JSON.createObjectNode().put("type", "boolean"))),
                true);
    }

    private static JsonNode requestBody(RecordedRequest request) throws IOException {
        assertNotNull(request);
        return JSON.readTree(request.getBody().readUtf8());
    }

    private static MockResponse success() {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\","
                        + "\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n"
                        + "data: [DONE]\n\n");
    }

    private static final class TestClient extends AbstractOpenAiCompatibleClient {
        private final String apiUrl;
        private final StructuredOutputCapability capability;

        private TestClient(String apiUrl, StructuredOutputCapability capability) {
            this.apiUrl = apiUrl;
            this.capability = capability;
        }

        @Override
        protected String getApiUrl() {
            return apiUrl;
        }

        @Override
        protected String getModel() {
            return "test-model";
        }

        @Override
        protected String getApiKey() {
            return "test-key";
        }

        @Override
        public StructuredOutputCapability structuredOutputCapability() {
            return capability;
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
