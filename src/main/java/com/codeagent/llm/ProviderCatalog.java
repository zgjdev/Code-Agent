package com.codeagent.llm;

import com.codeagent.config.ProviderDescriptor;
import java.util.List;
import static com.codeagent.config.ProviderDescriptor.EndpointPolicy.*;

/** The single source of defaults used by clients and configuration screens. */
final class ProviderCatalog {
    private ProviderCatalog() {}
    static final List<ProviderDescriptor> DESCRIPTORS = List.of(
            new ProviderDescriptor("glm", "GLM", "glm-5.1", "https://open.bigmodel.cn/api/coding/paas/v4", FIXED, true, false,
                    java.util.Map.of("glm-5v", "https://open.bigmodel.cn/api/paas/v4")),
            new ProviderDescriptor("deepseek", "DeepSeek", "deepseek-v4-flash", "https://api.deepseek.com", FIXED, true, false),
            new ProviderDescriptor("hunyuan", "Tencent Hunyuan", "hy4-preview", "https://tokenhub.tencentmaas.com/v1", EDITABLE, true, false),
            new ProviderDescriptor("step", "Step", "step-3.5-flash", "https://api.stepfun.com/v1", EDITABLE, true, false),
            new ProviderDescriptor("kimi", "Kimi", "kimi-k2.6", "https://api.moonshot.ai/v1", EDITABLE, true, false),
            new ProviderDescriptor("freellmapi", "FreeLLMAPI", "auto", "http://localhost:5173/v1", EDITABLE, true, false),
            new ProviderDescriptor("xfyun", "iFlytek MaaS", "Qwen3.6-35B-A3B", "https://maas-api.cn-huabei-1.xf-yun.com/v2", EDITABLE, true, true),
            new ProviderDescriptor("agnes", "Agnes", "agnes-2.0-flash", "https://apihub.agnes-ai.com/v1", EDITABLE, true, false));

    static ProviderDescriptor descriptor(String id) {
        return DESCRIPTORS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported provider"));
    }
}
