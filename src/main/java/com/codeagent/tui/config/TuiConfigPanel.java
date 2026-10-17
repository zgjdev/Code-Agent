package com.codeagent.tui.config;

import com.codeagent.cli.ProviderSetupController;
import com.codeagent.config.*;
import com.codeagent.llm.LlmClientFactory;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;

/** Shared configuration workflow. Saves affect future startups, never the live Agent. */
public final class TuiConfigPanel implements AutoCloseable {
    private final CodeAgentConfig activeConfiguration;
    private final ProviderConfigurationService service;
    private final WindowBasedTextGUI gui;
    private volatile TuiProviderSetupUi activeUi;

    public TuiConfigPanel(CodeAgentConfig config, WindowBasedTextGUI gui) {
        this(config, gui, new ProviderConfigurationService(new UserConfigStore(), LlmClientFactory.providerDescriptors()));
    }

    public TuiConfigPanel(CodeAgentConfig config, WindowBasedTextGUI gui, ProviderConfigurationService service) {
        this.activeConfiguration = config;
        this.gui = gui;
        this.service = service;
    }

    public void showConfigDialog() {
        try (var ui = new TuiProviderSetupUi(gui)) {
            activeUi = ui;
            ui.message(currentConfigurationSummary());
            new ProviderSetupController(service).run(ui);
        } finally {
            activeUi = null;
        }
    }

    private String currentConfigurationSummary() {
        StringBuilder summary = new StringBuilder("当前会话配置概览；Provider 修改重启后生效。\nHITL：始终启用\n");
        for (var descriptor : service.descriptors()) {
            var provider = activeConfiguration.getProviders().get(descriptor.id());
            if (provider == null) continue;
            summary.append(descriptor.displayName()).append("：温度=").append(provider.getTemperature())
                    .append("，最大输出 token=").append(provider.getMaxTokens()).append('\n');
        }
        return summary.toString();
    }

    /** Compatibility entry: persist a future default through the same conflict-aware store. */
    public void switchModel(String providerName) {
        if (providerName == null || providerName.isBlank()) return;
        try (var initial = service.newDraft(providerName);
             var draft = new ProviderSettingsDraft(initial.baseline(), providerName, null, null, null, SecretUpdate.keep(), true)) {
            service.save(draft);
        }
    }

    @Override public void close() {
        var ui = activeUi;
        if (ui != null) ui.close();
    }
}
