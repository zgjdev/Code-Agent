package com.codeagent.cli;

import java.util.Locale;
import java.util.Set;

public final class EmbeddingConfigCommandParser {
    private static final Set<String> REMOTE_PROVIDERS = Set.of("glm", "jina", "openai-compatible");

    public EmbeddingConfigCommand parse(String payload) {
        String value = payload == null ? "" : payload.trim();
        if (!value.toLowerCase(Locale.ROOT).startsWith("embedding")) {
            throw new IllegalArgumentException("不是 embedding 配置命令");
        }
        String arguments = value.substring("embedding".length()).trim();
        if (arguments.isEmpty() || "status".equalsIgnoreCase(arguments)) {
            return new EmbeddingConfigCommand(EmbeddingConfigCommand.Action.STATUS, null);
        }
        if ("local".equalsIgnoreCase(arguments)) {
            return new EmbeddingConfigCommand(EmbeddingConfigCommand.Action.LOCAL, null);
        }
        if ("off".equalsIgnoreCase(arguments)) {
            return new EmbeddingConfigCommand(EmbeddingConfigCommand.Action.OFF, null);
        }
        if ("revoke".equalsIgnoreCase(arguments)) {
            return new EmbeddingConfigCommand(EmbeddingConfigCommand.Action.REVOKE, null);
        }
        String[] parts = arguments.split("\\s+");
        if (parts.length == 2 && "remote".equalsIgnoreCase(parts[0])) {
            String provider = parts[1].toLowerCase(Locale.ROOT);
            if (REMOTE_PROVIDERS.contains(provider)) {
                return new EmbeddingConfigCommand(EmbeddingConfigCommand.Action.REMOTE_PROVIDER, provider);
            }
        }
        throw new IllegalArgumentException("未知 embedding 配置命令");
    }

    public record EmbeddingConfigCommand(Action action, String provider) {
        public enum Action { STATUS, LOCAL, OFF, REMOTE_PROVIDER, REVOKE }
    }
}
