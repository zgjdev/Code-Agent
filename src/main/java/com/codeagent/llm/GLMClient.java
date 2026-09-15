package com.codeagent.llm;

import com.codeagent.context.MeasuredUsage;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class GLMClient extends AbstractOpenAiCompatibleClient {

    private static final String CODING_API_URL = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";
    private static final String MULTIMODAL_API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String DEFAULT_MODEL = "glm-5.1";
    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public GLMClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public GLMClient(String apiKey, String model) {
        this(apiKey, model, null);
    }

    GLMClient(String apiKey, String model, String apiUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = apiUrl != null && !apiUrl.isBlank() ? apiUrl : selectApiUrl(this.model);
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
        return isGlm53();
    }

    @Override
    protected void customizeRequestBody(ObjectNode requestBody) {
        if (!isGlm53()) {
            return;
        }
        requestBody.put("temperature", 1.0);
        requestBody.put("top_p", 0.95);
        requestBody.put("reasoning_effort", "max");
        ObjectNode thinking = requestBody.putObject("thinking");
        thinking.put("type", "enabled");
        thinking.put("clear_thinking", false);
        requestBody.put("tool_stream", true);
        requestBody.putObject("stream_options").put("include_usage", true);
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "glm";
    }

    @Override
    public int maxContextWindow() {
        return isGlm53() ? 1_000_000 : 200_000;
    }

    @Override
    public boolean supportsImageInput() {
        return true;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "glm-prompt-cache";
    }

    /**
     * GLM prompt_tokens is the complete prompt total, including system and tools.
     * completion_tokens includes the assistant response and tool-call output.
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

    @Override
    protected String toImageUrl(LlmClient.ContentPart part) {
        if (isGlm5v() && "image_base64".equals(part.type())) {
            return part.imageBase64();
        }
        return super.toImageUrl(part);
    }

    private static String selectApiUrl(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase();
        if (normalized.startsWith("glm-5v")) {
            return MULTIMODAL_API_URL;
        }
        return CODING_API_URL;
    }

    private boolean isGlm5v() {
        return model != null && model.trim().toLowerCase().startsWith("glm-5v");
    }

    private boolean isGlm53() {
        return model != null && model.trim().toLowerCase().startsWith("glm-5.3");
    }
}
