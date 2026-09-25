package com.codeagent.rag.embedding;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleEmbeddingProviderTest {

    @Test
    void sendsNativeBatchAndAuthorization() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                    {"data":[{"index":0,"embedding":[1,0,0]},{"index":1,"embedding":[0,1,0]}]}
                    """).addHeader("Content-Type", "application/json"));
            OpenAiCompatibleEmbeddingProvider provider = provider(server, new OkHttpClient());

            List<float[]> result = provider.embedAll(List.of("one", "two"));

            assertEquals(2, result.size());
            var request = server.takeRequest();
            assertEquals("/v1/embeddings", request.getPath());
            assertEquals("Bearer secret-key", request.getHeader("Authorization"));
            String body = request.getBody().readUtf8();
            assertTrue(body.contains("\"input\":[\"one\",\"two\"]"));
            assertFalse(body.contains("Ollama"));
        }
    }

    @Test
    void retriesRateLimitThenSucceeds() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "0"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody(
                    "{\"data\":[{\"index\":0,\"embedding\":[1,0,0]}]}")
                    .addHeader("Content-Type", "application/json"));

            assertEquals(1, provider(server, new OkHttpClient()).embedAll(List.of("one")).size());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    void exposesStableErrorWithoutResponseBodyOrKey() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(400)
                    .setBody("upstream leaked secret-key and source code"));

            EmbeddingException error = assertThrows(EmbeddingException.class,
                    () -> provider(server, new OkHttpClient()).embedAll(List.of("private source")));

            assertEquals("remote_embedding_http_400", error.reasonCode());
            assertFalse(error.getMessage().contains("secret-key"));
            assertFalse(error.getMessage().contains("private source"));
        }
    }

    @Test
    void opensCircuitForSixtySecondsAfterThreeConsecutiveFailures() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(400));
            server.enqueue(new MockResponse().setResponseCode(400));
            server.enqueue(new MockResponse().setResponseCode(400));
            OpenAiCompatibleEmbeddingProvider provider = provider(server, new OkHttpClient());

            for (int i = 0; i < 3; i++) {
                assertThrows(EmbeddingException.class, () -> provider.embedAll(List.of("one")));
            }
            EmbeddingException open = assertThrows(EmbeddingException.class,
                    () -> provider.embedAll(List.of("one")));

            assertEquals("remote_embedding_circuit_open", open.reasonCode());
            assertEquals(3, server.getRequestCount(), "open circuit must fail without an HTTP request");
        }
    }

    private static OpenAiCompatibleEmbeddingProvider provider(
            MockWebServer server, OkHttpClient client) {
        return new OpenAiCompatibleEmbeddingProvider("test-remote", "test-model",
                server.url("/v1").toString(), "secret-key", 3, client, 3);
    }
}
