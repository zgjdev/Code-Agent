package com.codeagent.config;

/** Safe display model: the API key value is deliberately absent. */
public record ResolvedProviderSettings(String provider, String model, String baseUrl, String loraId,
        boolean apiKeyConfigured, SettingSource providerSource, SettingSource modelSource,
        SettingSource baseUrlSource, SettingSource loraIdSource, SettingSource apiKeySource) { }
