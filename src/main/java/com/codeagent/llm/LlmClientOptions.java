package com.codeagent.llm;

import okhttp3.OkHttpClient;
import java.time.Duration;
import java.util.Objects;

/** Instance-scoped transport/request options; never changes JVM properties or shared clients. */
public record LlmClientOptions(Duration connectTimeout, Duration callTimeout, int maxAttempts,
                               boolean configurationCheck) {
    /** Existing timeouts and dynamically resolved retry properties are preserved. */
    public static final LlmClientOptions DEFAULT = new LlmClientOptions(null, null, 0, false);
    public static final LlmClientOptions CONFIGURATION_CHECK =
            new LlmClientOptions(Duration.ofSeconds(5), Duration.ofSeconds(15), 1, true);

    public LlmClientOptions {
        if (configurationCheck) {
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(callTimeout, "callTimeout");
            if (connectTimeout.toMillis() <= 0 || connectTimeout.compareTo(Duration.ofSeconds(5)) > 0
                    || callTimeout.toMillis() <= 0 || callTimeout.compareTo(Duration.ofSeconds(15)) > 0
                    || maxAttempts != 1) {
                throw new IllegalArgumentException("Configuration checks require bounded timeouts and one attempt");
            }
        } else if (connectTimeout != null || callTimeout != null || maxAttempts != 0) {
            throw new IllegalArgumentException("Use DEFAULT for ordinary requests");
        }
    }

    OkHttpClient configure(OkHttpClient original) {
        if (!configurationCheck) return original;
        return original.newBuilder().connectTimeout(connectTimeout).callTimeout(callTimeout)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }
}
