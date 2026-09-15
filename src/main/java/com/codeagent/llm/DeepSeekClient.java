package com.codeagent.llm;

import com.codeagent.context.MeasuredUsage;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

import java.util.List;

public class DeepSeekClient extends AbstractOpenAiCompatibleClient {

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private static final OkHttpClient HTTP_1_1_CLIENT = SHARED_HTTP_CLIENT.newBuilder()
            .protocols(List.of(Protocol.HTTP_1_1))
            .build();
    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, API_URL);
    }

    DeepSeekClient(String apiKey, String model, String apiUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = apiUrl != null && !apiUrl.isBlank() ? apiUrl : API_URL;
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected boolean shouldSendReasoningContentInRequestHistory() {
        return true;
    }

    @Override
    protected void customizeRequestBody(ObjectNode requestBody) {
        if (!isDeepSeekV4()) {
            return;
        }
        requestBody.put("temperature", 1.0);
        requestBody.put("top_p", 0.95);
        requestBody.put("reasoning_effort", "max");
        requestBody.putObject("thinking").put("type", "enabled");
    }

    @Override
    public boolean supportsImageInput() {
        return false;
    }

    @Override
    protected OkHttpClient httpClient() {
        return HTTP_1_1_CLIENT;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public int maxContextWindow() {
        return 1_000_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "automatic-prefix-cache";
    }

    /**
     * DeepSeek prompt_tokens is the complete prompt total, including cached prefix
     * tokens. completion_tokens includes reasoning and assistant tool-call output.
     */
    @Override
    public MeasuredUsage normalizeUsage(ChatResponse response) {
        if (response == null || response.inputTokens() <= 0 || response.outputTokens() < 0
                || response.cachedInputTokens() < 0
                || response.cachedInputTokens() > response.inputTokens()) {
            return new MeasuredUsage(
                    Math.max(0, response == null ? 0 : response.inputTokens()),
                    Math.max(0, response == null ? 0 : response.outputTokens()),
                    Math.max(0, response == null ? 0 : response.cachedInputTokens()),
                    MeasuredUsage.InputScope.UNKNOWN,
                    false,
                    false,
                    false,
                    java.time.Instant.now());
        }
        return new MeasuredUsage(
                response.inputTokens(),
                response.outputTokens(),
                response.cachedInputTokens(),
                MeasuredUsage.InputScope.TOTAL_PROMPT,
                true,
                true,
                true,
                java.time.Instant.now());
    }

    private boolean isDeepSeekV4() {
        return model != null && model.trim().toLowerCase().startsWith("deepseek-v4-");
    }

}
