package com.codeagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexFileScannerTest {
    @Test
    void excludesSensitiveGeneratedIgnoredAndOutsideFiles(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".gitignore"), "ignored.java\nsecret-dir/\n");
        Files.writeString(root.resolve("Keep.java"), "class Keep {}");
        Files.writeString(root.resolve("ignored.java"), "class Ignored {}");
        Files.writeString(root.resolve(".env"), "API_KEY=secret");
        Files.createDirectories(root.resolve("secret-dir"));
        Files.writeString(root.resolve("secret-dir/Hidden.java"), "class Hidden {}");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/Generated.java"), "class Generated {}");
        Files.createDirectories(root.resolve(".codeagent/sessions"));
        Files.writeString(root.resolve(".codeagent/sessions/raw.jsonl"), "secret");

        IndexFileScanner.ScanResult result = new IndexFileScanner().scan(root);

        assertTrue(result.complete());
        assertTrue(result.files().stream().anyMatch(file -> file.relativePath().toString().equals("Keep.java")));
        assertFalse(result.files().stream().anyMatch(file -> file.relativePath().toString().contains("ignored")));
        assertFalse(result.files().stream().anyMatch(file -> file.relativePath().toString().contains(".env")));
        assertFalse(result.files().stream().anyMatch(file -> file.relativePath().toString().contains("target")));
        assertFalse(result.files().stream().anyMatch(file -> file.relativePath().toString().contains("sessions")));
    }

    @Test
    void refusesProjectOutsideConfiguredRoot(@TempDir Path root) {
        Path missing = root.resolve("missing");
        IndexFileScanner.ScanResult result = new IndexFileScanner().scan(missing);
        assertFalse(result.complete());
        assertTrue(result.files().isEmpty());
    }
}
