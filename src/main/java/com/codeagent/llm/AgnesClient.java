package com.codeagent.llm;

public class AgnesClient extends AbstractOpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = ProviderCatalog.descriptor("agnes").defaultBaseUrl();
    private static final String DEFAULT_MODEL = ProviderCatalog.descriptor("agnes").defaultModel();

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public AgnesClient(String apiKey, String model, String baseUrl) {
        this(apiKey, model, baseUrl, LlmClientOptions.DEFAULT);
    }

    public AgnesClient(String apiKey, String model, String baseUrl, LlmClientOptions options) {
        super(options);
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = toChatCompletionsUrl(baseUrl);
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
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "agnes";
    }

    @Override
    public int maxContextWindow() {
        return 1_000_000;
    }

    private static String toChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }
}
