package com.codeagent.config;

import com.codeagent.llm.LlmClient;
import com.codeagent.lsp.LspDiagnostic;
import com.codeagent.lsp.LspDiagnosticFormatter;
import com.codeagent.lsp.LspManager;
import com.codeagent.lsp.LspSeverity;
import com.codeagent.memory.AutoCompactionManager;
import com.codeagent.mcp.McpClient;
import com.codeagent.policy.AuditLog;
import com.codeagent.render.RendererFactory;
import com.codeagent.render.inline.TerminalCapabilities;
import com.codeagent.runtime.api.RuntimeApiServer;
import com.codeagent.runtime.api.RuntimeThreadStore;
import com.codeagent.runtime.task.DurableTaskManager;
import com.codeagent.snapshot.SnapshotConfig;
import com.codeagent.tui.TuiBootstrap;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DotEnvConfigurationIntegrationTest {
    @TempDir Path directory;
    private Properties saved;
    private Path project;
    private Path home;

    @BeforeEach void isolateConfiguration() throws Exception {
        org.slf4j.LoggerFactory.getLogger(DotEnvConfigurationIntegrationTest.class);
        saved = (Properties) System.getProperties().clone();
        project = Files.createDirectories(directory.resolve("project"));
        home = Files.createDirectories(directory.resolve("home"));
        System.setProperty("user.dir", project.toString());
        System.setProperty("user.home", home.toString());
        for (String key : saved.stringPropertyNames()) {
            if (key.startsWith("codeagent.")) System.clearProperty(key);
        }
    }

    @AfterEach void restoreConfiguration() { System.setProperties(saved); }

    private void dotenv(String content) throws Exception {
        // This suite tests dotenv fallback; process precedence is covered by ConfigurationValuesTest.
        for (String line : content.split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                String value = System.getenv(line.substring(0, separator));
                org.junit.jupiter.api.Assumptions.assumeTrue(value == null || value.isBlank(),
                        "Process environment overrides the dotenv fixture");
            }
        }
        Files.writeString(project.resolve(".env"), content);
    }

    @Test void rendererAndTuiUseTheSameDotEnvChoice() throws Exception {
        dotenv("CODEAGENT_RENDERER=lanterna\n");
        assertEquals(RendererFactory.Mode.LANTERNA, RendererFactory.resolveMode());
        Terminal terminal = mock(Terminal.class);
        when(terminal.getSize()).thenReturn(new Size(120, 40));
        assertTrue(TuiBootstrap.shouldUseTui(terminal));
        System.setProperty("codeagent.renderer", "plain");
        assertEquals(RendererFactory.Mode.PLAIN, RendererFactory.resolveMode());
        assertFalse(TuiBootstrap.shouldUseTui(terminal));
    }

    @Test void lspCanBeDisabledInDotEnv() throws Exception {
        dotenv("CODEAGENT_LSP_ENABLED=false\n");
        Path file = project.resolve("Broken.java");
        Files.writeString(file, "class Broken {");
        LspManager manager = new LspManager(project.toString());
        manager.runPostEditLspHook("Broken.java", file);
        assertTrue(manager.flushPendingDiagnostics().isEmpty());
    }

    @Test void diagnosticLimitComesFromDotEnv() throws Exception {
        dotenv("CODEAGENT_LSP_MAX_DIAGNOSTICS=1\n");
        var report = LspDiagnosticFormatter.format(List.of(
                new LspDiagnostic(LspSeverity.ERROR, "A.java", 1, 1, "first", "test"),
                new LspDiagnostic(LspSeverity.ERROR, "B.java", 2, 1, "second", "test")));
        assertTrue(report.promptText().contains("first"));
        assertFalse(report.promptText().contains("second"));
        assertTrue(report.promptText().contains("1"));
    }

    @Test void statusBarCanBeDisabledInDotEnv() throws Exception {
        dotenv("CODEAGENT_NO_STATUSBAR=true\n");
        Terminal terminal = mock(Terminal.class);
        when(terminal.getType()).thenReturn("xterm");
        when(terminal.getSize()).thenReturn(new Size(120, 40));
        assertFalse(TerminalCapabilities.supportsScrollRegion(terminal));
        System.setProperty("codeagent.no.statusbar", "false");
        assertTrue(TerminalCapabilities.supportsScrollRegion(terminal));
    }

    @Test void snapshotSettingsComeFromDotEnv() throws Exception {
        Path data = project.resolve("data");
        Path snapshots = data.resolve("snapshots");
        dotenv("CODEAGENT_SNAPSHOT_ENABLED=false\nCODEAGENT_SNAPSHOT_MAX=7\nCODEAGENT_SNAPSHOT_DIR="
                + project.resolve("ignored-snapshots") + "\nCODEAGENT_HOME=" + data
                + "\nCODEAGENT_SNAPSHOT_EXCLUDES=custom-dir\n");
        var config = SnapshotConfig.fromEnvironment();
        assertFalse(config.enabled());
        assertEquals(7, config.maxSnapshots());
        assertEquals(snapshots, config.snapshotsRoot());
        assertTrue(config.excludes().contains("custom-dir"));
        assertTrue(config.excludes().contains(".git"));
    }

    @Test void runtimeAndAuditSettingsComeFromDotEnv() throws Exception {
        Path data = project.resolve("data");
        Path tasks = data.resolve("tasks");
        Path runtime = data.resolve("runtime");
        Path audit = data.resolve("audit");
        dotenv("CODEAGENT_HOME=" + data + "\nCODEAGENT_TASK_DIR=" + project.resolve("ignored-tasks") + "\nCODEAGENT_RUNTIME_DIR=" + project.resolve("ignored-runtime")
                + "\nCODEAGENT_AUDIT_DIR=" + audit + "\nCODEAGENT_RUNTIME_API_KEY=fixture-key\n");
        assertEquals(tasks.resolve("tasks.db"), DurableTaskManager.defaultDbPath());
        assertEquals(runtime.resolve("runtime.db"), RuntimeThreadStore.defaultDbPath());
        assertEquals(audit, new AuditLog().getAuditDir());
        assertTrue("fixture-key".equals(RuntimeApiServer.configuredApiKey()));
        System.setProperty("codeagent.runtime.api.key", "property-key");
        assertTrue("property-key".equals(RuntimeApiServer.configuredApiKey()));
    }

    @Test void blankRuntimeKeyDoesNotEnableUnauthenticatedApi() throws Exception {
        Files.writeString(home.resolve(".env"), "CODEAGENT_RUNTIME_API_KEY=home-fixture\n");
        dotenv("CODEAGENT_RUNTIME_API_KEY=\n");
        assertTrue(RuntimeApiServer.configuredApiKey() == null);
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeApiServer(null, null, 0, RuntimeApiServer.configuredApiKey()));
    }

    @Test void mcpTimeoutComesFromDotEnv() throws Exception {
        dotenv("CODEAGENT_MCP_INITIALIZE_TIMEOUT_SECONDS=19\n");
        var method = McpClient.class.getDeclaredMethod("initializeTimeoutSeconds");
        method.setAccessible(true);
        assertEquals(19, method.invoke(null));
    }

    @Test void incrementalSessionSummaryStillReadsDotEnv() throws Exception {
        dotenv("CODEAGENT_SESSION_MEMORY_COMPACTION_ENABLED=true\n");
        var manager = new AutoCompactionManager(mock(LlmClient.class));
        assertTrue(manager.isSessionMemoryEnabled());
    }
}
