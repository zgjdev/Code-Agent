package com.codeagent.tool;

import java.util.List;
import java.util.Locale;

/** Selects the native command shell without making it user-configurable. */
final class CommandShell {
    private final boolean windows;

    private CommandShell(boolean windows) {
        this.windows = windows;
    }

    static CommandShell forCurrentPlatform() {
        return forOsName(System.getProperty("os.name", ""));
    }

    static CommandShell forOsName(String osName) {
        boolean windows = osName != null
                && osName.toLowerCase(Locale.ROOT).contains("win");
        return new CommandShell(windows);
    }

    List<String> command(String command) {
        if (windows) {
            return List.of(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command);
        }
        return List.of("bash", "-c", command);
    }

    boolean isWindows() {
        return windows;
    }
}
