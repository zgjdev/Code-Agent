package com.codeagent.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class IndexCommandParser {
    public IndexCommand parse(String payload) {
        if (payload == null || payload.isBlank()) return new IndexCommand(IndexCommand.Action.REFRESH, ".");
        String value = payload.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "status" -> new IndexCommand(IndexCommand.Action.STATUS, null);
            case "refresh" -> new IndexCommand(IndexCommand.Action.REFRESH, ".");
            case "rebuild" -> new IndexCommand(IndexCommand.Action.REBUILD, ".");
            case "clear" -> new IndexCommand(IndexCommand.Action.CLEAR, ".");
            default -> {
                Path path = Path.of(value);
                if (!Files.exists(path)) throw new IllegalArgumentException("未知 /index 子命令或路径不存在");
                yield new IndexCommand(IndexCommand.Action.REFRESH, value);
            }
        };
    }

    public record IndexCommand(Action action, String path) {
        public enum Action { STATUS, REFRESH, REBUILD, CLEAR }
    }
}
