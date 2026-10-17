package com.codeagent.llm;

import com.codeagent.config.CodeAgentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.codeagent.llm.ConnectionCheckResult.Status.*;

class ProviderConnectionCheckerTest {
    @Test void serviceUnavailableRetryAfterZeroDoesNotTriggerHiddenTransportReplay() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
            server.enqueue(success());
            var result = new ProviderConnectionChecker().check(config("step", server.url("/v1").toString()),
                    LlmClientOptions.CONFIGURATION_CHECK);
            assertEquals(UNKNOWN_ERROR, result.status());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test void malformedContentAndCompletionTypesAreNotSuccessfulChats() throws Exception {
        try (var server = new MockWebServer()) {
            for (String choice : new String[]{
                    "{\"delta\":{\"content\":42},\"finish_reason\":\"stop\"}",
                    "{\"delta\":{\"content\":true},\"finish_reason\":\"stop\"}",
                    "{\"delta\":{\"reasoning_content\":42},\"finish_reason\":\"stop\"}",
                    "{\"delta\":{\"content\":\"fixture\"},\"finish_reason\":\"invalid-reason\"}",
                    "{\"delta\":{\"content\":\"fixture\"},\"finish_reason\":true}"}) {
                server.enqueue(new MockResponse().setBody("data: {\"choices\":[" + choice + "]}\n\ndata: [DONE]\n\n"));
                assertEquals(UNKNOWN_ERROR, new ProviderConnectionChecker().check(config("step", server.url("/v1").toString()),
                        LlmClientOptions.CONFIGURATION_CHECK).status());
            }
        }
    }

    @Test void boundedReasoningOnlyReplyIsAValidChatButToolsAndWrongRolesAreRejected() throws Exception {
        try (var server = new MockWebServer()) {
            String[] deltas = {"{\"reasoning_content\":\"private-reasoning-fixture\"}",
                    "{\"role\":\"user\",\"content\":\"wrong-role\"}",
                    "{\"tool_calls\":[{\"index\":0,\"id\":\"x\",\"function\":{\"name\":\"fake\",\"arguments\":\"{}\"}}]}"};
            var statuses = new ConnectionCheckResult.Status[]{OK, UNKNOWN_ERROR, UNKNOWN_ERROR};
            for (int i = 0; i < deltas.length; i++) {
                server.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":" + deltas[i]
                        + ",\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n"));
                var result = new ProviderConnectionChecker().check(config("step", server.url("/v1").toString()),
                        LlmClientOptions.CONFIGURATION_CHECK);
                assertEquals(statuses[i], result.status());
                assertFalse(result.toString().contains("fixture"));
            }
        }
    }

    @Test void failuresDoNotLogRawBodyExceptionOrCredential() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AbstractOpenAiCompatibleClient.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try (var server = new MockWebServer()) {
            for (int code : new int[]{401, 429, 500}) {
                server.enqueue(new MockResponse().setResponseCode(code).setBody("synthetic-key-fixture"));
                var result = new ProviderConnectionChecker().check(config("step", server.url("/v1").toString()),
                        LlmClientOptions.CONFIGURATION_CHECK);
                assertFalse(result.message().contains("synthetic-key-fixture"));
            }
            assertTrue(appender.list.isEmpty(), "checks must not enter retry/raw-error logging");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void everyExistingProviderSendsBoundedOutputWithoutChangingOrdinaryDefaults() throws Exception {
        try (var server = new MockWebServer()) {
            var base = server.url("/v1").toString();
            var endpoint = server.url("/v1/chat/completions").toString();
            var options = LlmClientOptions.CONFIGURATION_CHECK;
            var clients = java.util.List.of(
                    new GLMClient("fixture", "glm-5.1", endpoint, options),
                    new GLMClient("fixture", "glm-5.3", endpoint, options),
                    new DeepSeekClient("fixture", "deepseek-v4-flash", endpoint, options),
                    new HunyuanClient("fixture", null, base, options),
                    new StepClient("fixture", "step-3.5-flash-2603", base, options),
                    new KimiClient("fixture", null, base, options),
                    new FreeLlmApiClient("fixture", null, base, options),
                    new XfyunMaaSClient("fixture", null, base, "0", options),
                    new AgnesClient("fixture", null, base, options));
            for (var client : clients) {
                server.enqueue(success());
                client.chat(java.util.List.of(LlmClient.Message.user("Reply briefly with OK.")), java.util.List.of());
                var request = server.takeRequest();
                var body = new ObjectMapper().readTree(request.getBody().readUtf8());
                assertEquals(16, body.path("max_tokens").asInt(), client.getProviderName());
                assertFalse(body.has("tools"), client.getProviderName());
                assertFalse(body.has("response_format"), client.getProviderName());
                assertEquals(1, body.path("messages").size());
                if (client instanceof DeepSeekClient) assertEquals("http/1.1", client.httpClient().protocols().get(0).toString());
                if (client instanceof XfyunMaaSClient) assertEquals("0", request.getHeader("lora_id"));
            }
            server.enqueue(success());
            new StepClient("fixture", null, base).chat(java.util.List.of(LlmClient.Message.user("normal")), null);
            assertFalse(new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8()).has("max_tokens"));
        }
    }

    @Test void mapsTypedNetworkAndTlsErrorsWithoutInspectingMessages() {
        assertEquals(TLS_ERROR, ProviderConnectionChecker.classify(new javax.net.ssl.SSLException("synthetic-secret")));
        assertEquals(NETWORK_ERROR, ProviderConnectionChecker.classify(new java.net.UnknownHostException("synthetic-secret")));
        assertEquals(NETWORK_ERROR, ProviderConnectionChecker.classify(new java.net.ConnectException("synthetic-secret")));
        assertEquals(TIMEOUT, ProviderConnectionChecker.classify(new java.net.SocketTimeoutException("synthetic-secret")));
        assertEquals(UNKNOWN_ERROR, ProviderConnectionChecker.classify(new java.io.IOException("synthetic-secret timeout")));
    }

    @Test void refusesUnboundedDefaultOptionsAndRepeatedExecution() throws Exception {
        try (var server = new MockWebServer()) {
            var checker = new ProviderConnectionChecker();
            var config = config("step", server.url("/v1").toString());
            assertThrows(IllegalArgumentException.class, () -> checker.newCheck(config, LlmClientOptions.DEFAULT));
            var session = checker.newCheck(config, LlmClientOptions.CONFIGURATION_CHECK);
            session.close();
            assertEquals(CANCELED, session.execute().status());
            assertThrows(IllegalStateException.class, session::execute);
        }
    }

    @Test void sendsOneTinyChatAndDiscardsResponse() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(success());
            var result = new ProviderConnectionChecker().check(config("step", server.url("/v1").toString()),
                    LlmClientOptions.CONFIGURATION_CHECK);
            assertEquals(OK, result.status());
            assertFalse(result.toString().contains("secret-returned-by-model"));
            var request = server.takeRequest();
            var body = new ObjectMapper().readTree(request.getBody().readUtf8());
            assertEquals(16, body.path("max_tokens").asInt());
            assertFalse(body.has("tools"));
            assertEquals(1, body.path("messages").size());
            assertEquals("Reply briefly with OK.", body.path("messages").get(0).path("content").asText());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test void classifiesHttpFailuresWithoutResponseBodyOrRetry() throws Exception {
        int[] codes = {400, 401, 403, 404, 429, 500};
        var expected = new ConnectionCheckResult.Status[]{MODEL_OR_ENDPOINT_INVALID, AUTH_FAILED,
                AUTH_FAILED, MODEL_OR_ENDPOINT_INVALID, RATE_LIMITED, UNKNOWN_ERROR};
        try (var server = new MockWebServer()) {
            for (int i = 0; i < codes.length; i++) {
                server.enqueue(new MockResponse().setResponseCode(codes[i]).setBody("secret-server-body-fixture"));
                var result = new ProviderConnectionChecker().check(config("step", server.url("/v1").toString()),
                        LlmClientOptions.CONFIGURATION_CHECK);
                assertEquals(expected[i], result.status());
                assertFalse(result.toString().contains("secret"));
            }
            assertEquals(codes.length, server.getRequestCount());
        }
    }

    @Test void refusesUnsafeEndpointsBeforeNetwork() {
        for (String url : new String[]{"http://remote.example/v1", "https://user:secret@example.com/v1",
                "https://example.com/v1?api_key=secret", "https://example.com/v1#secret", "file:///secret"}) {
            assertEquals(MODEL_OR_ENDPOINT_INVALID, new ProviderConnectionChecker().check(config("step", url),
                    LlmClientOptions.CONFIGURATION_CHECK).status());
        }
    }

    @Test void doesNotForwardCredentialsToRedirectTarget() throws Exception {
        try (var source = new MockWebServer(); var target = new MockWebServer()) {
            target.start();
            source.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", target.url("/steal")));
            var result = new ProviderConnectionChecker().check(config("step", source.url("/v1").toString()),
                    LlmClientOptions.CONFIGURATION_CHECK);
            assertEquals(MODEL_OR_ENDPOINT_INVALID, result.status());
            assertEquals(0, target.getRequestCount());
        }
    }

    @Test void htmlAndEmptyStreamsAreNotSuccessfulChats() throws Exception {
        try (var server = new MockWebServer()) {
            for (String response : new String[]{"<html>secret</html>", "data: [DONE]\n\n"}) {
                server.enqueue(new MockResponse().setBody(response));
                assertEquals(UNKNOWN_ERROR, new ProviderConnectionChecker().check(config("step", server.url("/v1").toString()),
                        LlmClientOptions.CONFIGURATION_CHECK).status());
            }
        }
    }

    @Test void boundedCallTimeoutStopsBlockedResponse() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var options = new LlmClientOptions(Duration.ofMillis(200), Duration.ofMillis(300), 1, true);
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertEquals(TIMEOUT,
                    new ProviderConnectionChecker().check(config("step", server.url("/v1").toString()), options).status()));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test void cancellationAbortsBlockedOkHttpCallWithoutWaitingForTimeout() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var session = new ProviderConnectionChecker().newCheck(config("step", server.url("/v1").toString()),
                    LlmClientOptions.CONFIGURATION_CHECK);
            var future = executor.submit(session::execute);
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            session.cancel();
            assertEquals(CANCELED, future.get(2, TimeUnit.SECONDS).status());
            assertEquals(1, server.getRequestCount());
        } finally { executor.shutdownNow(); }
    }

    @Test void cancelBeforeExecuteDoesNotSendRequest() throws Exception {
        try (var server = new MockWebServer()) {
            var session = new ProviderConnectionChecker().newCheck(config("step", server.url("/v1").toString()),
                    LlmClientOptions.CONFIGURATION_CHECK);
            session.cancel();
            assertEquals(CANCELED, session.execute().status());
            assertEquals(0, server.getRequestCount());
        }
    }

    private static CodeAgentConfig config(String provider, String baseUrl) {
        var config = new CodeAgentConfig() {
            @Override public String getApiKey(String ignored) { return "synthetic-key-fixture"; }
            @Override public String getBaseUrl(String ignored) { return baseUrl; }
            @Override public String getModel(String ignored) { return "fixture-model"; }
        };
        config.setDefaultProvider(provider);
        return config;
    }
    private static MockResponse success() {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"secret-returned-by-model\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
    }
}
