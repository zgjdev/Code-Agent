package com.codeagent.cli;

import com.codeagent.config.*;
import com.codeagent.llm.*;
import java.net.URI;
import java.util.*;
import java.util.function.Function;

/** UI-only configuration state machine. No Agent, ledger, workspace lease, or runtime services. */
public final class ProviderSetupController {
    public enum Outcome { SAVED, CANCELED, FAILED }
    public interface Probe extends AutoCloseable {
        ConnectionCheckResult execute();
        void cancel();
        @Override default void close() { cancel(); }
    }
    public interface Ui {
        boolean secureInputAvailable();
        void message(String message);
        int choose(String title, List<String> options);
        /** Empty means keep; null means cancel. Values are never printed by the controller. */
        String text(String title, String current);
        char[] secret(String title);
        ConnectionCheckResult check(Probe probe);
    }
    private final ProviderConfigurationService service;
    private final Function<CodeAgentConfig, Probe> probeFactory;
    public ProviderSetupController(ProviderConfigurationService service) {
        this(service, config -> {
            var check = new ProviderConnectionChecker().newCheck(config, LlmClientOptions.CONFIGURATION_CHECK);
            return new Probe() {
                public ConnectionCheckResult execute() { return check.execute(); }
                public void cancel() { check.cancel(); }
            };
        });
    }
    public ProviderSetupController(ProviderConfigurationService service, Function<CodeAgentConfig, Probe> probeFactory) {
        this.service = Objects.requireNonNull(service); this.probeFactory = Objects.requireNonNull(probeFactory);
    }
    public Outcome run(Ui ui) {
        if (!ui.secureInputAvailable()) { ui.message("请在真实交互终端运行 codeagent config，以安全隐藏输入密钥。"); return Outcome.FAILED; }
        try {
            service.readSnapshot(); // A damaged document must never become a blank draft.
            while (true) {
                var descriptors = service.descriptors();
                int selected = ui.choose("选择服务商（取消保留现有配置）", descriptors.stream()
                        .map(d -> d.displayName() + " / 默认模型: " + d.defaultModel()).toList());
                if (selected < 0 || selected >= descriptors.size()) return Outcome.CANCELED;
                ProviderDescriptor descriptor = descriptors.get(selected);
                try (var initial = service.newDraft(descriptor.id())) {
                    var resolved = service.resolve(initial.baseline(), descriptor.id());
                    boolean safeExistingEndpoint = validEndpoint(resolved.baseUrl());
                    ui.message("配置来源：模型=" + sourceLabel(resolved.modelSource()) + "，地址=" + sourceLabel(resolved.baseUrlSource())
                            + "，密钥=" + (resolved.apiKeyConfigured() ? "已配置" : "未配置") + "（" + sourceLabel(resolved.apiKeySource()) + "）。");
                    String model = ui.text("模型（回车保留）", safeText(resolved.model(), descriptor.defaultModel()));
                    if (model == null) return Outcome.CANCELED;
                    String baseUrl = null;
                    if (descriptor.endpointPolicy() == ProviderDescriptor.EndpointPolicy.EDITABLE) {
                        if (!safeExistingEndpoint) ui.message("现有地址格式不安全，请重新填写 HTTPS 地址或本机回环 HTTP 地址。");
                        baseUrl = ui.text("服务地址（回车保留）", safeExistingEndpoint ? resolved.baseUrl() : descriptor.defaultBaseUrl());
                        if (baseUrl == null) return Outcome.CANCELED;
                        if (!safeExistingEndpoint && baseUrl.isBlank()) { ui.message("必须重新填写有效服务地址。"); continue; }
                    }
                    String lora = null;
                    if (descriptor.supportsLoraId()) {
                        lora = ui.text("资源 ID / loraId（回车保留）", safeText(resolved.loraId(), ""));
                        if (lora == null) return Outcome.CANCELED;
                    }
                    char[] chars = ui.secret(resolved.apiKeyConfigured() ? "API Key（隐藏输入，回车保留）" : "API Key（隐藏输入）");
                    if (chars == null) return Outcome.CANCELED;
                    try (var draft = new ProviderSettingsDraft(initial.baseline(), descriptor.id(), edited(model), edited(baseUrl), edited(lora),
                                 chars.length == 0 ? SecretUpdate.keep() : SecretUpdate.replace(chars), true)) {
                        Arrays.fill(chars, '\0');
                        service.validateDraft(draft);
                        var temporary = service.temporaryConfig(draft);
                        String endpoint = temporary.getBaseUrl(descriptor.id());
                        ProviderEndpoint.validate(endpoint);
                        String actualModel = temporary.getModel(descriptor.id());
                        if (descriptor.requiresKey() && !ApiKeyValidation.isHeaderSafe(temporary.getApiKey(descriptor.id()))) {
                            ui.message("API Key 缺失或格式无效，请重新填写。"); continue;
                        }
                        ui.message("这份配置：" + descriptor.displayName() + "，模型=" + safeText(actualModel, "默认")
                                + "，目标主机=" + URI.create(endpoint).getHost() + "；密钥已配置（不显示）。");
                        try {
                            var active = service.resolve();
                            if (!active.provider().equals(descriptor.id()) && active.providerSource() != SettingSource.USER_CONFIG && active.providerSource() != SettingSource.DEFAULT)
                                ui.message("本次环境指定了另一服务商（来源=" + sourceLabel(active.providerSource()) + "），修改默认设置不会覆盖它。");
                        } catch (IllegalArgumentException ignored) {
                            ui.message("当前服务商选择无效；Java 属性或环境选择仍可能覆盖已保存的默认设置。");
                        }
                        boolean verified = false;
                        while (true) {
                            int action = ui.choose("测试会发送一个短请求，可能产生少量调用费用；只测试上述配置", List.of("测试连接", "保存但暂不测试 / 保存配置", "返回修改", "取消"));
                            if (action < 0 || action == 3) return Outcome.CANCELED;
                            if (action == 2) break;
                            if (action == 0) {
                                try (var probe = probeFactory.apply(temporary)) {
                                    var result = ui.check(probe);
                                    verified = result.status() == ConnectionCheckResult.Status.OK;
                                    ui.message(result.message());
                                }
                                continue;
                            }
                            if (action == 1) {
                                service.save(draft);
                                ui.message(verified ? "配置已保存，下次启动自动使用；本次短请求验证成功。" : "配置已保存，下次启动自动使用；尚未验证。");
                                return Outcome.SAVED;
                            }
                        }
                    } finally { Arrays.fill(chars, '\0'); }
                }
            }
        } catch (UserConfigStore.ConfigStoreException failure) {
            ui.message(saveFailure(failure)); return Outcome.FAILED;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            ui.message("配置字段无效；请重新运行配置向导检查模型和服务地址。"); return Outcome.FAILED;
        }
    }
    public static boolean validEndpoint(String endpoint) {
        try { ProviderEndpoint.validate(endpoint); return true; } catch (RuntimeException e) { return false; }
    }
    public static String sourceLabel(SettingSource source) {
        return switch (source) {
            case DEFAULT -> "默认值";
            case USER_CONFIG -> "用户配置";
            case PROCESS_ENV -> "当前终端环境";
            case PROJECT_ENV -> "项目环境文件";
            case USER_ENV -> "用户环境文件";
            case JAVA_PROPERTY -> "Java 启动参数";
        };
    }
    private static String edited(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String safeText(String value, String fallback) {
        return value == null || value.chars().anyMatch(Character::isISOControl) ? fallback : value;
    }
    public static String saveFailure(UserConfigStore.ConfigStoreException failure) {
        return switch (failure.reason()) {
            case BUSY -> "配置正被其他进程写入，请稍后重试；未保存。";
            case CONFLICT -> "配置已被其他操作修改，请重新加载向导；未保存。";
            case INVALID_CONFIG -> "配置文件损坏或格式不受支持，已保留原文件；请检查配置文件。";
            case PERMISSIONS -> "无法设置当前用户专属文件权限；未保存。";
            case UNSAFE_PATH -> "配置路径不安全；未保存。";
            case ATOMIC_MOVE_UNSUPPORTED -> "文件系统不支持安全原子替换；未保存。";
            case IO -> "无法读写配置文件，请检查磁盘和目录权限；未保存。";
        };
    }
}
