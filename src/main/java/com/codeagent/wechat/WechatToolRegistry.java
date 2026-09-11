package com.codeagent.wechat;

import com.codeagent.policy.AuditLog;
import com.codeagent.tool.ToolOutput;
import com.codeagent.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;

public class WechatToolRegistry extends ToolRegistry {
    private final WechatPolicyDecider decider;

    public WechatToolRegistry(WechatPolicyDecider decider) {
        this.decider = decider;
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        long start = System.nanoTime();
        WechatPolicyDecision decision = decider == null
                ? WechatPolicyDecision.allow()
                : decider.decide(name, argumentsJson);
        if (!decision.allowed()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(
                    name,
                    argumentsJson,
                    decision.reason(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
            return ToolOutput.failure("微信通道策略拒绝: " + decision.reason());
        }
        return super.doExecuteTool(name, argumentsJson);
    }
}
