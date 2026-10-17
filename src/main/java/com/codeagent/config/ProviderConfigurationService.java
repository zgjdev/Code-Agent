package com.codeagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Configuration-only service. Network validation belongs to the caller's explicit test action. */
public final class ProviderConfigurationService {
    private final UserConfigStore store;
    private final List<ProviderDescriptor> descriptors;
    private final ConfigurationValues.EnvironmentSnapshot environment;
    private final Properties properties;
    public ProviderConfigurationService(UserConfigStore store, List<ProviderDescriptor> descriptors) {
        this(store, descriptors, ConfigurationValues.currentEnvironmentSnapshot(), System.getProperties());
    }
    public ProviderConfigurationService(UserConfigStore store, List<ProviderDescriptor> descriptors,
            ConfigurationValues.EnvironmentSnapshot environment, Properties properties) {
        this.store = Objects.requireNonNull(store); this.descriptors = List.copyOf(descriptors);
        this.environment = Objects.requireNonNull(environment);
        this.properties = new Properties(); this.properties.putAll(properties);
    }
    public List<ProviderDescriptor> descriptors() { return descriptors; }
    public UserConfigStore.Snapshot readSnapshot() { return store.readSnapshot(); }
    public ProviderDescriptor descriptor(String provider) {
        String id = normalize(provider);
        return descriptors.stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported provider"));
    }
    public ProviderSettingsDraft newDraft(String provider) {
        return new ProviderSettingsDraft(store.readSnapshot(), descriptor(provider).id(), null, null, null, SecretUpdate.keep(), false);
    }
    public ResolvedProviderSettings resolve() { return resolveSnapshot(store.readSnapshot(), null); }
    public ResolvedProviderSettings resolve(String provider) {
        descriptor(provider);
        return resolveSnapshot(store.readSnapshot(), provider);
    }
    /** Resolve the same immutable baseline that the UI is editing, without a second file read. */
    public ResolvedProviderSettings resolve(UserConfigStore.Snapshot snapshot, String provider) {
        descriptor(provider);
        return resolveSnapshot(Objects.requireNonNull(snapshot), provider);
    }

    private ResolvedProviderSettings resolveSnapshot(UserConfigStore.Snapshot snapshot, String selectedProvider) {
        JsonNode raw = snapshot.document();
        ProviderSelection selection = selection(snapshot);
        String id = normalize(selectedProvider == null ? selection.configuredId() : selectedProvider);
        String configuredId = configuredId(selection, selectedProvider == null ? selection.configuredId() : selectedProvider);
        SettingSource selectionSource = selectedProvider == null ? selection.source() : SettingSource.DEFAULT;
        ProviderDescriptor descriptor = descriptor(id);
        var config = CodeAgentConfig.fromSnapshot(snapshot, environment.values());
        var model = field(config, raw, id, configuredId, "model", "MODEL");
        var endpoint = field(config, raw, id, configuredId, "baseUrl", "BASE_URL");
        var key = field(config, raw, id, configuredId, "apiKey", "API_KEY");
        var lora = field(config, raw, id, configuredId, "loraId", "LORA_ID");
        String selectedModel = fallback(model.value, descriptor.defaultModel());
        String baseUrl = descriptor.endpointPolicy() == ProviderDescriptor.EndpointPolicy.FIXED
                ? descriptor.endpointForModel(selectedModel) : fallback(endpoint.value, descriptor.defaultBaseUrl());
        return new ResolvedProviderSettings(id, selectedModel, baseUrl, descriptor.supportsLoraId() ? lora.value : null,
                ApiKeyValidation.isHeaderSafe(key.value), selectionSource, blank(model.value) ? SettingSource.DEFAULT : model.source,
                descriptor.endpointPolicy() == ProviderDescriptor.EndpointPolicy.FIXED || blank(endpoint.value) ? SettingSource.DEFAULT : endpoint.source,
                descriptor.supportsLoraId() ? lora.source : SettingSource.DEFAULT, key.source);
    }

    private record ProviderSelection(String configuredId, SettingSource source) { }
    private ProviderSelection selection(UserConfigStore.Snapshot snapshot) {
        JsonNode raw = snapshot.document();
        String selected = properties.getProperty("codeagent.provider");
        SettingSource selectionSource = SettingSource.JAVA_PROPERTY;
        if (blank(selected)) { selected = environment.values().get("CODEAGENT_PROVIDER"); selectionSource = environment.source("CODEAGENT_PROVIDER"); }
        if (blank(selected)) { selected = text(raw.path("defaultProvider")); selectionSource = blank(selected) ? SettingSource.DEFAULT : SettingSource.USER_CONFIG; }
        if (blank(selected)) selected = "glm";
        return new ProviderSelection(selected.trim().toLowerCase(Locale.ROOT), selectionSource);
    }
    private static String configuredId(ProviderSelection selection, String requestedProvider) {
        // Canonical UI choices must retain the active selector's alias-only JSON fallback.
        return normalize(requestedProvider).equals(normalize(selection.configuredId()))
                ? selection.configuredId() : requestedProvider.trim().toLowerCase(Locale.ROOT);
    }

    public void validateDraft(ProviderSettingsDraft draft) {
        ProviderDescriptor descriptor = descriptor(draft.provider());
        for (String value : new String[]{draft.model(), draft.baseUrl(), draft.loraId()}) {
            if (value != null && (value.isBlank() || value.chars().anyMatch(Character::isISOControl))) throw new IllegalArgumentException("Invalid provider setting");
        }
        if (draft.baseUrl() != null) {
            if (descriptor.endpointPolicy() == ProviderDescriptor.EndpointPolicy.FIXED) throw new IllegalArgumentException("Provider endpoint is fixed");
            ProviderEndpoint.validate(draft.baseUrl());
        }
        if (draft.loraId() != null && !descriptor.supportsLoraId()) throw new IllegalArgumentException("Provider does not support loraId");
    }

    public CodeAgentConfig temporaryConfig(ProviderSettingsDraft draft) {
        validateDraft(draft);
        var descriptor = descriptor(draft.provider());
        var config = CodeAgentConfig.fromSnapshot(draft.baseline(), environment.values());
        String id = descriptor.id();
        JsonNode raw = draft.baseline().document();
        String configuredId = configuredId(selection(draft.baseline()), id);
        String key = draft.secretUpdate().action() == SecretUpdate.Action.REPLACE ? draft.secretUpdate().replacement()
                : field(config, raw, id, configuredId, "apiKey", "API_KEY").value;
        String selectedModel = fallback(draft.model(), fallback(field(config, raw, id, configuredId, "model", "MODEL").value, descriptor.defaultModel()));
        var provider = new CodeAgentConfig.ProviderConfig(key,
                descriptor.endpointPolicy() == ProviderDescriptor.EndpointPolicy.FIXED ? descriptor.endpointForModel(selectedModel)
                        : fallback(draft.baseUrl(), fallback(field(config, raw, id, configuredId, "baseUrl", "BASE_URL").value, descriptor.defaultBaseUrl())),
                selectedModel);
        provider.setLoraId(fallback(draft.loraId(), field(config, raw, id, configuredId, "loraId", "LORA_ID").value));
        var original = config.getProviders().get(id);
        if (original != null) { provider.setTemperature(original.getTemperature()); provider.setMaxTokens(original.getMaxTokens()); }
        config.getProviders().put(id, provider); config.setDefaultProvider(id);
        return config;
    }

    public UserConfigStore.Snapshot save(ProviderSettingsDraft draft) {
        validateDraft(draft);
        return store.saveProvider(draft, configuredId(selection(draft.baseline()), draft.provider()));
    }

    private static final class SettingValue {
        final String value; final SettingSource source;
        SettingValue(String value, SettingSource source) { this.value = value; this.source = source; }
        @Override public String toString() { return "SettingValue[redacted]"; }
    }
    private SettingValue field(CodeAgentConfig config, JsonNode raw, String id, String configuredId, String name, String suffix) {
        String value = value(config, id, name);
        SettingSource source = source(raw.path("providers").path(id), id, name, suffix);
        if (!blank(value) || configuredId.equals(id)) return new SettingValue(value, source);
        // Match the factory: an alias key fallback may use explicit JSON only, never evade a blank canonical environment key.
        String aliasValue = "apiKey".equals(name) ? text(raw.path("providers").path(configuredId).path(name)) : value(config, configuredId, name);
        return blank(aliasValue) ? new SettingValue(value, source)
                : new SettingValue(aliasValue, source(raw.path("providers").path(configuredId), configuredId, name, suffix));
    }
    private static String value(CodeAgentConfig config, String id, String name) {
        return switch (name) {
            case "apiKey" -> config.getApiKey(id);
            case "model" -> config.getModel(id);
            case "baseUrl" -> config.getBaseUrl(id);
            case "loraId" -> config.getLoraId(id);
            default -> throw new IllegalArgumentException("Unsupported setting");
        };
    }
    private SettingSource source(JsonNode provider, String id, String field, String suffix) {
        if (!blank(text(provider.path(field)))) return SettingSource.USER_CONFIG;
        String prefix = id.equals("xfyun") ? "XFYUN_MAAS" : id.toUpperCase(Locale.ROOT);
        String key = prefix + "_" + suffix;
        if (environment.values().containsKey(key)) return environment.source(key);
        String alias = id.equals("kimi") ? "MOONSHOT_" + suffix : id.equals("xfyun") ? "XFYUN_" + suffix : null;
        return alias == null ? SettingSource.DEFAULT : environment.source(alias);
    }
    private static String text(JsonNode node) { return node.isTextual() ? node.textValue() : null; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String fallback(String value, String fallback) { return blank(value) ? fallback : value; }
    private static String normalize(String value) { return ProviderDescriptor.normalizeId(value); }
}
