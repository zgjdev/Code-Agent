package com.codeagent.cli;

/** Pure process-argument parsing, before filesystem/logging/runtime initialization. */
public final class StartupCommandParser {
    public enum Kind { INTERACTIVE, CONFIG, HELP, VERSION, SERVE, ERROR }
    public record Command(Kind kind, int port) { }
    private StartupCommandParser() { }
    public static Command parse(String[] args) {
        if (args == null || args.length == 0) return new Command(Kind.INTERACTIVE, 8080);
        if (args.length == 1) return new Command(switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "--help", "-h" -> Kind.HELP;
            case "--version", "-v" -> Kind.VERSION;
            case "config" -> Kind.CONFIG;
            default -> Kind.ERROR;
        }, 8080);
        if (!"serve".equalsIgnoreCase(args[0])) return new Command(Kind.ERROR, 8080);
        boolean http = false, hasPort = false; int port = 8080;
        for (int i = 1; i < args.length; i++) {
            if ("--http".equalsIgnoreCase(args[i]) && !http) http = true;
            else if ("--port".equalsIgnoreCase(args[i]) && !hasPort && i + 1 < args.length) {
                hasPort = true;
                try { port = Integer.parseInt(args[++i]); }
                catch (NumberFormatException e) { return new Command(Kind.ERROR, 8080); }
                if (port < 1 || port > 65535) return new Command(Kind.ERROR, 8080);
            } else return new Command(Kind.ERROR, 8080);
        }
        return new Command(http ? Kind.SERVE : Kind.ERROR, port);
    }
    public static String usage() {
        return """
                CodeAgent — 在当前项目目录运行 Agent（Java 17+）
                用法: codeagent [config|--help|--version]
                      codeagent serve --http [--port <1-65535>]
                首次使用请在交互终端运行 codeagent，或使用 codeagent config。
                配置向导隐藏输入密钥；运行中的 /config 可重新配置，重启后生效。
                Windows: 解压安装包后运行 install-codeagent.ps1，重新打开终端。
                """.stripTrailing();
    }
}
