package com.codeagent.cli;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainConfigBootstrapTest {

    @Test
    void appliesWebToolRoutesToRegistry() {
        CodeAgentConfig config = new CodeAgentConfig();
        CodeAgentConfig.WebToolRouteConfig search = new CodeAgentConfig.WebToolRouteConfig();
        search.setBackend("mcp");
        search.setTool("mcp__anysearch__search");
        config.getWebTools().setSearch(search);
        ToolRegistry registry = new ToolRegistry();

        Main.configureToolRegistry(registry, config);

        assertEquals("mcp__anysearch__search",
                registry.getWebToolsConfig().getSearch().getTool());
    }

    @Test
    void createsDefaultChromeDevtoolsMcpConfigWhenMissing(@TempDir Path tempHome) throws Exception {
        Main.McpConfigBootstrapResult result = Main.ensureDefaultMcpConfig(tempHome);

        Path config = tempHome.resolve(".codeagent").resolve("mcp.json");
        assertTrue(result.created());
        assertTrue(Files.exists(config));
        String content = Files.readString(config);
        assertTrue(content.contains("\"chrome-devtools\""));
        assertTrue(content.contains("chrome-devtools-mcp@latest"));
        assertTrue(content.contains("--isolated=true"));
    }

    @Test
    void doesNotOverwriteExistingUserConfig(@TempDir Path tempHome) throws Exception {
        Path config = tempHome.resolve(".codeagent").resolve("mcp.json");
        Files.createDirectories(config.getParent());
        String original = """
                {
                  "mcpServers": {
                    "filesystem": {
                      "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-filesystem"]
                    }
                  }
                }
                """;
        Files.writeString(config, original);

        Main.McpConfigBootstrapResult result = Main.ensureDefaultMcpConfig(tempHome);

        assertFalse(result.created());
        assertEquals(original, Files.readString(config));
        assertTrue(result.message().contains("未配置 chrome-devtools"));
    }

    @Test
    void startupRejectsMissingSelectedKeyWithoutTryingAnotherProvider() throws Exception {
        CodeAgentConfig config = new CodeAgentConfig() {
            @Override public String getApiKey(String provider) {
                return "glm".equals(provider) ? "fixture-other-provider" : null;
            }
        };
        config.setDefaultProvider("deepseek");
        var captured = new java.io.ByteArrayOutputStream();
        var original = System.err;
        try (var output = new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(output);
            assertNull(Main.createStartupClient(config));
        } finally {
            System.setErr(original);
        }
        String message = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(message.contains("deepseek"));
        assertFalse(message.contains("fixture-other-provider"));
    }

    @Test
    void startupRejectsInvalidProviderWithoutEchoingConfigurationText() throws Exception {
        CodeAgentConfig config = new CodeAgentConfig();
        config.setDefaultProvider("invalid-sensitive-fixture");
        var captured = new java.io.ByteArrayOutputStream();
        var original = System.err;
        try (var output = new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(output);
            assertNull(Main.createStartupClient(config));
        } finally {
            System.setErr(original);
        }
        String message = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(message.contains("CODEAGENT_PROVIDER"));
        assertFalse(message.contains("invalid-sensitive-fixture"));
    }
}
