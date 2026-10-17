package com.codeagent.cli;

import com.codeagent.config.*;

/** Local-only validation; never constructs a client or sends network requests. */
public final class StartupOnboarding {
    private StartupOnboarding() { }
    public static boolean valid(ProviderConfigurationService service) {
        try {
            var active = service.resolve(); var descriptor = service.descriptor(active.provider());
            return (!descriptor.requiresKey() || active.apiKeyConfigured())
                    && active.model() != null && !active.model().isBlank()
                    && active.model().chars().noneMatch(Character::isISOControl)
                    && ProviderSetupController.validEndpoint(active.baseUrl());
        } catch (IllegalArgumentException | UserConfigStore.ConfigStoreException failure) { return false; }
    }
    /** 0 permits runtime startup, all other codes stop before runtime construction. */
    public static int ensure(ProviderConfigurationService service, ProviderSetupController.Ui ui, boolean configOnly) {
        try { service.readSnapshot(); }
        catch (UserConfigStore.ConfigStoreException failure) { ui.message(ProviderSetupController.saveFailure(failure)); return 1; }
        if (!configOnly && valid(service)) return 0;
        if (!ui.secureInputAvailable()) {
            ui.message("模型配置无效或缺失。请在交互终端运行 codeagent config；管道和重定向不会读取配置输入。"); return 1;
        }
        var result = new ProviderSetupController(service).run(ui);
        if (result == ProviderSetupController.Outcome.FAILED) return 1;
        if (result == ProviderSetupController.Outcome.CANCELED) return configOnly ? 0 : 1;
        if (!configOnly && !valid(service)) {
            String source;
            try { source = ProviderSetupController.sourceLabel(service.resolve().providerSource()); }
            catch (RuntimeException ignored) { source = "Java 启动参数 / 当前终端环境 / 项目环境文件 / 用户环境文件 / 用户配置"; }
            ui.message("已保存，但当前有效模型配置仍无效（选择来源=" + source + "）。请检查覆盖来源或运行 codeagent config；本次不进入运行时。"); return 1;
        }
        return 0;
    }
}
