package com.codeagent.llm;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.config.ProviderEndpoint;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.codeagent.llm.ConnectionCheckResult.Status.*;

/** Independent, opt-in short chat; no tools, project data, ledger, trace logger, or runtime. */
public final class ProviderConnectionChecker {
    public ConnectionCheckResult check(CodeAgentConfig config, LlmClientOptions options) {
        return newCheck(config, options).execute();
    }

    /**
     * Create on the UI thread, execute on its worker, cancel from Ctrl+C/EOF/close handlers.
     * cancel() directly cancels the active OkHttp Call, including blocked reads/connects.
     * Each session is single use; Future.cancel alone is not the cancellation contract.
     */
    public CheckSession newCheck(CodeAgentConfig config, LlmClientOptions options) {
        Objects.requireNonNull(options);
        if (!options.configurationCheck()) throw new IllegalArgumentException("Connection checks require check options");
        try {
            var client = (AbstractOpenAiCompatibleClient) LlmClientFactory.createFromConfig(config, options);
            ProviderEndpoint.validate(client.getApiUrl());
            return new CheckSession(client, null);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return new CheckSession(null, new ConnectionCheckResult(MODEL_OR_ENDPOINT_INVALID));
        }
    }

    public static final class CheckSession implements AutoCloseable {
        private final AbstractOpenAiCompatibleClient client;
        private final ConnectionCheckResult preparationFailure;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean canceled = new AtomicBoolean();

        private CheckSession(AbstractOpenAiCompatibleClient client, ConnectionCheckResult preparationFailure) {
            this.client = client;
            this.preparationFailure = preparationFailure;
        }

        public ConnectionCheckResult execute() {
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("Connection check already executed");
            if (canceled.get()) return new ConnectionCheckResult(CANCELED);
            if (preparationFailure != null) return preparationFailure;
            try {
                var response = client.chat(List.of(LlmClient.Message.user("Reply briefly with OK.")), List.of());
                if (canceled.get()) return new ConnectionCheckResult(CANCELED);
                if (!"assistant".equals(response.role()) || response.toolCalls() != null && !response.toolCalls().isEmpty()) {
                    return new ConnectionCheckResult(UNKNOWN_ERROR);
                }
                return new ConnectionCheckResult(OK);
            } catch (IOException failure) {
                if (canceled.get()) return new ConnectionCheckResult(CANCELED);
                return new ConnectionCheckResult(classify(failure));
            } catch (RuntimeException failure) {
                return new ConnectionCheckResult(canceled.get() ? CANCELED : UNKNOWN_ERROR);
            }
        }

        public void cancel() {
            canceled.set(true);
            if (client != null) client.cancelActiveRequest();
        }
        @Override public void close() { cancel(); }
    }

    static ConnectionCheckResult.Status classify(IOException failure) {
        if (failure instanceof LlmHttpException http) {
            return switch (http.statusCode()) {
                case 401, 403 -> AUTH_FAILED;
                case 400, 404, 405, 422, 301, 302, 303, 307, 308 -> MODEL_OR_ENDPOINT_INVALID;
                case 429 -> RATE_LIMITED;
                case 408, 504 -> TIMEOUT;
                default -> UNKNOWN_ERROR;
            };
        }
        if (failure instanceof SSLException) return TLS_ERROR;
        if (failure instanceof InterruptedIOException) return TIMEOUT;
        if (failure instanceof ConnectException || failure instanceof UnknownHostException
                || failure instanceof NoRouteToHostException || failure instanceof SocketException) return NETWORK_ERROR;
        return UNKNOWN_ERROR;
    }
}
