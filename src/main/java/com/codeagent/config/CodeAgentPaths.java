package com.codeagent.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** The single root for application-owned data. Resolving a path does not create it. */
public final class CodeAgentPaths {
    private CodeAgentPaths() {}

    public static Path root() {
        return resolveRoot(ConfigurationValues.get("codeagent.home", "CODEAGENT_HOME", null),
                Path.of(System.getProperty("user.home")));
    }

    static Path resolveRoot(String configured, Path userHome) {
        if (configured == null || configured.isBlank()) {
            return userHome.resolve(".codeagent").toAbsolutePath().normalize();
        }
        String value = configured.trim();
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("CODEAGENT_HOME must not contain control characters");
        }
        Path path;
        if (value.equals("~")) {
            path = userHome;
        } else if (value.startsWith("~/") || value.startsWith("~\\")) {
            path = userHome.resolve(value.substring(2));
        } else {
            path = Path.of(value);
        }
        // In particular, Windows /tmp and C:tmp are not fully qualified absolute paths.
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("CODEAGENT_HOME 必须为完整绝对路径或 ~/ 路径；Windows 路径必须包含盘符或 UNC 共享名。");
        }
        return path.normalize();
    }

    public static Path resolve(String relative) {
        Path child = Path.of(relative);
        Path root = root();
        Path result = root.resolve(child).normalize();
        if (child.isAbsolute() || result.equals(root) || !result.startsWith(root)) {
            throw new IllegalArgumentException("Application data path must be below CODEAGENT_HOME");
        }
        return result;
    }

    /** Called before logging, native libraries, or workers are initialized. */
    public static void initialize() throws IOException {
        Path root = root();
        Path workspace = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (workspace.startsWith(root)) {
            throw new IllegalArgumentException("CodeAgent 数据目录不能是当前工作区或其父目录；请选择独立的数据目录。");
        }
        Path temporary = root.resolve("tmp");
        Files.createDirectories(temporary);
        Files.createDirectories(root.resolve("logs"));
        System.setProperty("codeagent.home", root.toString());
        System.setProperty("codeagent.log.dir", root.resolve("logs").toString());
        System.setProperty("java.io.tmpdir", temporary.toString());
        System.setProperty("org.sqlite.tmpdir", temporary.toString());
    }

    /** Standard child-process caches; this does not confine arbitrary external side effects. */
    public static void configureProcessEnvironment(Map<String, String> environment) throws IOException {
        Path temporary = resolve("tmp");
        Path npmCache = resolve("cache/npm");
        Files.createDirectories(temporary);
        Files.createDirectories(npmCache);
        environment.keySet().removeIf(key -> key.equalsIgnoreCase("TMP") || key.equalsIgnoreCase("TEMP")
                || key.equalsIgnoreCase("TMPDIR") || key.equalsIgnoreCase("npm_config_cache"));
        environment.put("TMP", temporary.toString());
        environment.put("TEMP", temporary.toString());
        environment.put("TMPDIR", temporary.toString());
        environment.put("npm_config_cache", npmCache.toString());
        environment.put("CODEAGENT_HOME", root().toString());
    }

    public static Path workspaceReports(Path workspace) {
        try {
            Path canonical = workspace.toAbsolutePath().normalize();
            if (Files.exists(canonical)) canonical = canonical.toRealPath();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
            return resolve("reports/better-harness").resolve(hash);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to identify report workspace", e);
        }
    }
}
