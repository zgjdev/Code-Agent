package com.codeagent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeAgentConfig {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".codeagent");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private String defaultProvider = "glm";
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    private EmbeddingConfig embedding = new EmbeddingConfig();

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
        private String mode;
        private String provider;
        private String model;
        private String baseUrl;
        private String apiKey;
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
        public String getApiKey() { return trimToNull(apiKey); }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }

        private void applyEnvironment(Map<String, String> environment) {
            if (isBlank(mode)) mode = environment.get("EMBEDDING_MODE");
            if (isBlank(provider)) provider = environment.get("EMBEDDING_PROVIDER");
            if (isBlank(model)) model = environment.get("EMBEDDING_MODEL");
            if (isBlank(baseUrl)) baseUrl = environment.get("EMBEDDING_BASE_URL");
            if (isBlank(apiKey)) apiKey = environment.get("EMBEDDING_API_KEY");
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
        return load(CONFIG_FILE, System.getenv());
    }

    public static CodeAgentConfig load(Path configFile, Map<String, String> environment) {
        CodeAgentConfig config = new CodeAgentConfig();
        if (Files.exists(configFile)) {
            try {
                config = mapper.readValue(configFile.toFile(), CodeAgentConfig.class);
            } catch (IOException e) {
                System.err.println("⚠️ 配置文件读取失败，使用默认配置: " + e.getMessage());
            }
        }
        config.getEmbedding().applyEnvironment(environment == null ? Map.of() : environment);
        return config;
    }

    public void save() {
        save(CONFIG_FILE);
    }

    public void save(Path configFile) {
        try {
            Path parent = configFile.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            mapper.writeValue(configFile.toFile(), this);
        } catch (IOException e) {
            System.err.println("⚠️ 配置保存失败: " + e.getMessage());
        }
    }

    private static String loadModelFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "glm" -> "GLM_MODEL";
            case "deepseek" -> "DEEPSEEK_MODEL";
            case "hunyuan" -> "HUNYUAN_MODEL";
            case "kimi" -> "KIMI_MODEL";
            case "freellmapi" -> "FREELLMAPI_MODEL";
            case "xfyun" -> "XFYUN_MAAS_MODEL";
            default -> provider.toUpperCase() + "_MODEL";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_MODEL");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_MODEL");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        if ("xfyun".equalsIgnoreCase(provider)) {
            String xfyunValue = System.getenv("XFYUN_MODEL");
            if (xfyunValue != null && !xfyunValue.isBlank()) {
                return xfyunValue.trim();
            }
            String xfyunDotEnvValue = readFromDotEnv("XFYUN_MODEL");
            if (xfyunDotEnvValue != null && !xfyunDotEnvValue.isBlank()) {
                return xfyunDotEnvValue.trim();
            }
        }

        return null;
    }

    private static String loadApiKeyFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "glm" -> "GLM_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "hunyuan" -> "HUNYUAN_API_KEY";
            case "step" -> "STEP_API_KEY";
            case "kimi" -> "KIMI_API_KEY";
            case "freellmapi" -> "FREELLMAPI_API_KEY";
            case "xfyun" -> "XFYUN_MAAS_API_KEY";
            default -> provider.toUpperCase() + "_API_KEY";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_API_KEY");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_API_KEY");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        if ("xfyun".equalsIgnoreCase(provider)) {
            String xfyunValue = System.getenv("XFYUN_API_KEY");
            if (xfyunValue != null && !xfyunValue.isBlank()) {
                return xfyunValue.trim();
            }
            String xfyunDotEnvValue = readFromDotEnv("XFYUN_API_KEY");
            if (xfyunDotEnvValue != null && !xfyunDotEnvValue.isBlank()) {
                return xfyunDotEnvValue.trim();
            }
        }

        return null;
    }

    private static String loadBaseUrlFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "step" -> "STEP_BASE_URL";
            case "hunyuan" -> "HUNYUAN_BASE_URL";
            case "kimi" -> "KIMI_BASE_URL";
            case "freellmapi" -> "FREELLMAPI_BASE_URL";
            case "xfyun" -> "XFYUN_MAAS_BASE_URL";
            default -> provider.toUpperCase() + "_BASE_URL";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_BASE_URL");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_BASE_URL");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        if ("xfyun".equalsIgnoreCase(provider)) {
            String xfyunValue = System.getenv("XFYUN_BASE_URL");
            if (xfyunValue != null && !xfyunValue.isBlank()) {
                return xfyunValue.trim();
            }
            String xfyunDotEnvValue = readFromDotEnv("XFYUN_BASE_URL");
            if (xfyunDotEnvValue != null && !xfyunDotEnvValue.isBlank()) {
                return xfyunDotEnvValue.trim();
            }
        }

        return null;
    }

    private static String loadLoraIdFromEnv(String provider) {
        if (!"xfyun".equalsIgnoreCase(provider)) {
            return null;
        }

        String envValue = System.getenv("XFYUN_MAAS_LORA_ID");
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv("XFYUN_MAAS_LORA_ID");
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        String xfyunValue = System.getenv("XFYUN_LORA_ID");
        if (xfyunValue != null && !xfyunValue.isBlank()) {
            return xfyunValue.trim();
        }

        String xfyunDotEnvValue = readFromDotEnv("XFYUN_LORA_ID");
        if (xfyunDotEnvValue != null && !xfyunDotEnvValue.isBlank()) {
            return xfyunDotEnvValue.trim();
        }
        return null;
    }

    private static String readFromDotEnv(String key) {
        File[] envFiles = { new File(".env"), new File(System.getProperty("user.home"), ".env") };
        for (File envFile : envFiles) {
            if (!envFile.exists()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith(key + "=")) {
                        return line.substring((key + "=").length()).trim();
                    }
                }
            } catch (IOException ignored) {}
        }
        return null;
    }
}
