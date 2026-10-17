package com.codeagent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeAgentConfig {

    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private String defaultProvider = "glm";
    @JsonIgnore
    private Map<String, String> environmentValues;
    @JsonIgnore
    private UserConfigStore.Snapshot loadedSnapshot;
    @JsonIgnore
    private com.fasterxml.jackson.databind.node.ObjectNode loadedView;
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    private EmbeddingConfig embedding = new EmbeddingConfig();
    private AutoIndexConfig autoIndex = new AutoIndexConfig();

    public AutoIndexConfig getAutoIndex() { return autoIndex; }
    public void setAutoIndex(AutoIndexConfig options) {
        autoIndex = options == null ? new AutoIndexConfig() : options;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AutoIndexConfig {
        private boolean enabled = true;
        private long debounceMillis = 1500;
        private long maxDebounceMillis = 10000;
        private long reconcileIntervalSeconds = 300;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public long getDebounceMillis() { return debounceMillis; }
        public void setDebounceMillis(long value) { debounceMillis = value; }
        public long getMaxDebounceMillis() { return maxDebounceMillis; }
        public void setMaxDebounceMillis(long value) { maxDebounceMillis = value; }
        public long getReconcileIntervalSeconds() { return reconcileIntervalSeconds; }
        public void setReconcileIntervalSeconds(long value) { reconcileIntervalSeconds = value; }
        public void validate() {
            if (debounceMillis < 0 || maxDebounceMillis < Math.max(1, debounceMillis)
                    || reconcileIntervalSeconds < 1 || reconcileIntervalSeconds > 86400
                    || maxDebounceMillis > 60000)
                throw new IllegalArgumentException("Invalid autoIndex timing configuration");
        }
    }
    private WebToolsConfig webTools = new WebToolsConfig();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String model;
        private String loraId;
        private double temperature = 0.7;  // 默认温度
        private int maxTokens = 8192;      // 默认最大 token 数

        public ProviderConfig() {}

        public ProviderConfig(String apiKey, String baseUrl, String model) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getLoraId() { return loraId; }
        public void setLoraId(String loraId) { this.loraId = loraId; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbeddingConfig {
        private String localModelDirectory;
        public String getLocalModelDirectory() { return trimToNull(localModelDirectory); }
        public void setLocalModelDirectory(String directory) { localModelDirectory = directory; }
        private String mode;
        private String provider;
        private String model;
        private String baseUrl;
        private String apiKey;
        @JsonIgnore
        private String environmentApiKey;
        private int dimension;

        public EmbeddingConfig() {}

        public String getMode() {
            return isBlank(mode) ? "local" : mode.trim();
        }

        public void setMode(String mode) { this.mode = mode; }
        public String getProvider() { return trimToNull(provider); }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return trimToNull(model); }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return trimToNull(baseUrl); }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        @JsonIgnore
        public String getApiKey() { return isBlank(apiKey) ? trimToNull(environmentApiKey) : trimToNull(apiKey); }
        @JsonProperty("apiKey")
        public String getPersistedApiKey() { return trimToNull(apiKey); }
        @JsonProperty("apiKey")
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }

        private void applyEnvironment(Map<String, String> environment) {
            if (isBlank(localModelDirectory)) localModelDirectory = environment.get("EMBEDDING_LOCAL_MODEL_DIR");
            if (isBlank(mode)) mode = environment.get("EMBEDDING_MODE");
            if (isBlank(provider)) provider = environment.get("EMBEDDING_PROVIDER");
            if (isBlank(model)) model = environment.get("EMBEDDING_MODEL");
            if (isBlank(baseUrl)) baseUrl = environment.get("EMBEDDING_BASE_URL");
            environmentApiKey = environment.get("EMBEDDING_API_KEY");
        }

        @Override
        public String toString() {
            return "EmbeddingConfig{" +
                    "mode='" + getMode() + '\'' +
                    ", provider='" + getProvider() + '\'' +
                    ", model='" + getModel() + '\'' +
                    ", baseUrl='" + getBaseUrl() + '\'' +
                    ", dimension=" + dimension +
                    ", apiKey='" + (getApiKey() == null ? null : "***") + '\'' +
                    '}';
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static String trimToNull(String value) {
            return isBlank(value) ? null : value.trim();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WebToolsConfig {
        private WebToolRouteConfig search = WebToolRouteConfig.autoDefault();
        private WebToolRouteConfig fetch = WebToolRouteConfig.autoDefault();

        public WebToolsConfig() {}

        public WebToolRouteConfig getSearch() {
            if (search == null) search = WebToolRouteConfig.autoDefault();
            return search;
        }

        public void setSearch(WebToolRouteConfig search) {
            this.search = search == null ? WebToolRouteConfig.autoDefault() : search;
        }

        public WebToolRouteConfig getFetch() {
            if (fetch == null) fetch = WebToolRouteConfig.autoDefault();
            return fetch;
        }

        public void setFetch(WebToolRouteConfig fetch) {
            this.fetch = fetch == null ? WebToolRouteConfig.autoDefault() : fetch;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WebToolRouteConfig {
        private String backend;
        private String provider;
        private String tool;
        private String onUnavailable;

        public WebToolRouteConfig() {}

        private WebToolRouteConfig(String backend) {
            this.backend = backend;
        }

        public static WebToolRouteConfig autoDefault() {
            return new WebToolRouteConfig("auto");
        }

        public String getBackend() { return normalize(backend); }
        public void setBackend(String backend) { this.backend = backend; }
        public String getProvider() { return normalize(provider); }
        public void setProvider(String provider) { this.provider = provider; }
        public String getTool() { return trimToNull(tool); }
        public void setTool(String tool) { this.tool = tool; }
        public String getOnUnavailable() {
            String normalized = normalize(onUnavailable);
            return normalized == null ? ("auto".equals(getBackend()) ? "step" : "fail") : normalized;
        }
        public void setOnUnavailable(String onUnavailable) { this.onUnavailable = onUnavailable; }

        public String validationError(String logicalTool) {
            String logical = logicalTool == null ? "" : logicalTool.trim().toLowerCase();
            String selectedBackend = getBackend();
            String anyTool;
            String stepTool;
            if ("web_search".equals(logical)) {
                anyTool = "mcp__anysearch__search";
                stepTool = "mcp__step_search__web_search";
            } else if ("web_fetch".equals(logical)) {
                anyTool = "mcp__anysearch__extract";
                stepTool = "mcp__step_search__web_fetch";
            } else {
                return "未知 Web 逻辑工具: " + logicalTool;
            }
            if ((!"auto".equals(selectedBackend) && !"mcp".equals(selectedBackend))
                    || getProvider() != null
                    || (getTool() != null && !anyTool.equals(getTool()) && !stepTool.equals(getTool()))
                    || ("mcp".equals(selectedBackend) && getTool() == null)
                    || ("auto".equals(selectedBackend) && getTool() != null)) {
                return logical + " 已统一为 AnySearch/Step MCP：使用 auto 或 mcp 工具 " + anyTool + " / " + stepTool;
            }
            String unavailable = getOnUnavailable();
            if (!"fail".equals(unavailable) && !"step".equals(unavailable) && !"default".equals(unavailable)) {
                return logical + " onUnavailable 必须是 fail 或 step（default 为兼容别名）";
            }
            return null;
        }

        private static String normalize(String value) {
            String trimmed = trimToNull(value);
            return trimmed == null ? null : trimmed.toLowerCase(java.util.Locale.ROOT);
        }

        private static String trimToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }


    }

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }
    public EmbeddingConfig getEmbedding() {
        if (embedding == null) embedding = new EmbeddingConfig();
        return embedding;
    }
    public void setEmbedding(EmbeddingConfig embedding) {
        this.embedding = embedding == null ? new EmbeddingConfig() : embedding;
    }
    public WebToolsConfig getWebTools() {
        if (webTools == null) webTools = new WebToolsConfig();
        return webTools;
    }
    public void setWebTools(WebToolsConfig webTools) {
        this.webTools = webTools == null ? new WebToolsConfig() : webTools;
    }

    public String getApiKey(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getApiKey() != null && !providerConfig.getApiKey().isBlank()) {
            return providerConfig.getApiKey();
        }
        return loadApiKeyFromEnv(provider);
    }

    public String getModel(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getModel() != null && !providerConfig.getModel().isBlank()) {
            return providerConfig.getModel();
        }
        return loadModelFromEnv(provider);
    }

    public String getBaseUrl(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getBaseUrl() != null && !providerConfig.getBaseUrl().isBlank()) {
            return providerConfig.getBaseUrl();
        }
        return loadBaseUrlFromEnv(provider);
    }

    public String getLoraId(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getLoraId() != null && !providerConfig.getLoraId().isBlank()) {
            return providerConfig.getLoraId();
        }
        return loadLoraIdFromEnv(provider);
    }

    public static CodeAgentConfig load() {
        Map<String, String> values = new LinkedHashMap<>(ConfigurationValues.currentEnvironment());
        String selected = ConfigurationValues.get("codeagent.provider", "CODEAGENT_PROVIDER", null);
        if (selected != null) values.put("CODEAGENT_PROVIDER", selected);
        return load(CodeAgentPaths.resolve("config.json"), values);
    }

    public static CodeAgentConfig load(Path configFile, Map<String, String> environment) {
        CodeAgentConfig config = new CodeAgentConfig();
        try {
            var snapshot = new UserConfigStore(configFile).readSnapshot();
            config = mapper.treeToValue(snapshot.document(), CodeAgentConfig.class);
            config.loadedSnapshot = snapshot;
        } catch (IOException | UserConfigStore.ConfigStoreException e) {
            System.err.println("⚠️ 配置文件读取失败，使用默认配置；请检查配置文件格式与访问权限。");
        }
        config.environmentValues = environment == null ? Map.of() : Map.copyOf(environment);
        String selected = config.environmentValues.get("CODEAGENT_PROVIDER");
        if (selected != null && !selected.isBlank()) {
            config.setDefaultProvider(selected.trim().toLowerCase(java.util.Locale.ROOT));
        }
        config.getEmbedding().applyEnvironment(config.environmentValues);
        config.loadedView = mapper.valueToTree(config);
        // Preserve the legacy initial-save default selection contract without persisting environment keys.
        if (config.loadedSnapshot != null && !config.loadedSnapshot.exists()) config.loadedView = null;
        return config;
    }

    public void save() {
        save(CodeAgentPaths.resolve("config.json"));
    }

    static CodeAgentConfig fromSnapshot(UserConfigStore.Snapshot snapshot, Map<String, String> environment) {
        try {
            CodeAgentConfig config = mapper.treeToValue(snapshot.document(), CodeAgentConfig.class);
            config.environmentValues = Map.copyOf(environment);
            config.getEmbedding().applyEnvironment(config.environmentValues);
            config.loadedSnapshot = snapshot;
            config.loadedView = mapper.valueToTree(config);
            return config;
        } catch (IOException | IllegalArgumentException e) {
            throw new UserConfigStore.ConfigStoreException(UserConfigStore.Failure.INVALID_CONFIG);
        }
    }

    public void save(Path configFile) {
        var view = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(this);
        loadedSnapshot = new UserConfigStore(configFile).saveChanges(loadedSnapshot, loadedView, view);
        loadedView = view.deepCopy();
    }

    private String configuredValue(String key) {
        String value = environmentValues == null ? ConfigurationValues.get(null, key, null) : environmentValues.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String loadModelFromEnv(String provider) {
        String key = "xfyun".equalsIgnoreCase(provider) ? "XFYUN_MAAS_MODEL"
                : provider.toUpperCase(java.util.Locale.ROOT) + "_MODEL";
        return withLegacyAlias(provider, key, "MODEL");
    }

    private String loadApiKeyFromEnv(String provider) {
        String key = "xfyun".equalsIgnoreCase(provider) ? "XFYUN_MAAS_API_KEY"
                : provider.toUpperCase(java.util.Locale.ROOT) + "_API_KEY";
        return withLegacyAlias(provider, key, "API_KEY");
    }

    private String loadBaseUrlFromEnv(String provider) {
        String key = "xfyun".equalsIgnoreCase(provider) ? "XFYUN_MAAS_BASE_URL"
                : provider.toUpperCase(java.util.Locale.ROOT) + "_BASE_URL";
        return withLegacyAlias(provider, key, "BASE_URL");
    }

    private String loadLoraIdFromEnv(String provider) {
        return "xfyun".equalsIgnoreCase(provider)
                ? withLegacyAlias(provider, "XFYUN_MAAS_LORA_ID", "LORA_ID") : null;
    }

    private String withLegacyAlias(String provider, String key, String suffix) {
        Map<String, String> values = environmentValues == null ? ConfigurationValues.currentEnvironment() : environmentValues;
        if (values.containsKey(key)) {
            String value = values.get(key);
            return value == null || value.isBlank() ? null : value.trim();
        }
        if ("kimi".equalsIgnoreCase(provider)) return configuredValue("MOONSHOT_" + suffix);
        if ("xfyun".equalsIgnoreCase(provider)) return configuredValue("XFYUN_" + suffix);
        return null;
    }
}
