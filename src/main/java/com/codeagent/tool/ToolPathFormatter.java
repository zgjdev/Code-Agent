package com.codeagent.tool;

import java.nio.file.Path;

/** Stable project-relative path formatting for tool output shown to models and users. */
final class ToolPathFormatter {
    private ToolPathFormatter() {
    }

    static String portable(Path path) {
        return portable(path == null ? "" : path.toString());
    }

    static String portable(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }
}
