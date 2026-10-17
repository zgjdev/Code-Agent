package com.codeagent.llm;

import java.util.Objects;

/** Contains only typed status and static descriptions; never raw exceptions or model responses. */
public record ConnectionCheckResult(Status status) {
    public enum Status {
        OK, AUTH_FAILED, MODEL_OR_ENDPOINT_INVALID, RATE_LIMITED, NETWORK_ERROR,
        TLS_ERROR, TIMEOUT, CANCELED, UNKNOWN_ERROR
    }
    public ConnectionCheckResult { Objects.requireNonNull(status); }
    public String message() {
        return switch (status) {
            case OK -> "连接测试成功（仅验证当前地址和模型的短对话）";
            case AUTH_FAILED -> "凭证或访问权限失败";
            case MODEL_OR_ENDPOINT_INVALID -> "请检查 Provider、模型或服务地址";
            case RATE_LIMITED -> "请求受限，请检查限流或额度";
            case NETWORK_ERROR -> "网络连接失败，请检查地址与网络";
            case TLS_ERROR -> "TLS 安全连接失败";
            case TIMEOUT -> "连接测试超时";
            case CANCELED -> "连接测试已取消";
            case UNKNOWN_ERROR -> "未能完成有效对话，请检查服务配置";
        };
    }
}
