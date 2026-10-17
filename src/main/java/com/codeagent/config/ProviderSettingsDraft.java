package com.codeagent.config;

import java.util.Objects;

/** Null text fields mean unedited, never implicit clear. Close every discarded draft. */
public final class ProviderSettingsDraft implements AutoCloseable {
    private final UserConfigStore.Snapshot baseline;
    private final String provider, model, baseUrl, loraId;
    private final SecretUpdate secretUpdate;
    private final boolean makeDefault;
    public ProviderSettingsDraft(UserConfigStore.Snapshot baseline, String provider, String model,
                                 String baseUrl, String loraId, SecretUpdate secretUpdate, boolean makeDefault) {
        this.baseline = Objects.requireNonNull(baseline);
        this.provider = ProviderDescriptor.normalizeId(Objects.requireNonNull(provider));
        if (!this.provider.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid provider identifier");
        this.model = model; this.baseUrl = baseUrl; this.loraId = loraId;
        this.secretUpdate = Objects.requireNonNull(secretUpdate); this.makeDefault = makeDefault;
    }
    public UserConfigStore.Snapshot baseline() { return baseline; }
    public String provider() { return provider; }
    public String model() { return model; }
    public String baseUrl() { return baseUrl; }
    public String loraId() { return loraId; }
    public SecretUpdate secretUpdate() { return secretUpdate; }
    public boolean makeDefault() { return makeDefault; }
    @Override public void close() { secretUpdate.close(); }
    @Override public String toString() { return "ProviderSettingsDraft[redacted]"; }
}
