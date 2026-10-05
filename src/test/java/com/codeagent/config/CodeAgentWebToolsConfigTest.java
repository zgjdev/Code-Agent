package com.codeagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAgentWebToolsConfigTest {

    @Test
    void defaultsToAnySearchAndAutomaticFetch(@TempDir Path tempDir) {
        CodeAgentConfig config = CodeAgentConfig.load(tempDir.resolve("missing.json"), Map.of());

        assertEquals("auto", config.getWebTools().getSearch().getBackend());
        assertEquals("auto", config.getWebTools().getFetch().getBackend());
        assertEquals("step", config.getWebTools().getSearch().getOnUnavailable());
        assertEquals("step", config.getWebTools().getFetch().getOnUnavailable());
        assertNull(config.getWebTools().getSearch().validationError("web_search"));
        assertNull(config.getWebTools().getFetch().validationError("web_fetch"));
    }

    @Test
    void loadsExplicitMcpRoutes(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, """
                {
                  "webTools": {
                    "search": {
                      "backend": "mcp",
                      "tool": "mcp__anysearch__search",
                      "onUnavailable": "fail"
                    },
                    "fetch": {
                      "backend": "mcp",
                      "tool": "mcp__step_search__web_fetch"
                    }
                  }
                }
                """);

        CodeAgentConfig config = CodeAgentConfig.load(configFile, Map.of());

        assertEquals("mcp", config.getWebTools().getSearch().getBackend());
        assertEquals("mcp__anysearch__search", config.getWebTools().getSearch().getTool());
        assertEquals("fail", config.getWebTools().getSearch().getOnUnavailable());
        assertEquals("mcp", config.getWebTools().getFetch().getBackend());
        assertEquals("mcp__step_search__web_fetch", config.getWebTools().getFetch().getTool());
        assertEquals("fail", config.getWebTools().getFetch().getOnUnavailable());
    }

    @Test
    void preservesInvalidRouteForFailClosedValidation(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, """
                {"webTools":{"search":{"backend":"mcp","tool":"web_search","onUnavailable":"sometimes"}}}
                """);

        CodeAgentConfig.WebToolRouteConfig route = CodeAgentConfig.load(configFile, Map.of())
                .getWebTools().getSearch();

        assertEquals("mcp", route.getBackend());
        assertTrue(route.validationError("web_search").contains("mcp__"));
    }

    @Test
    void nullRoutesFromJsonRestoreSafeDefaults(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, """
                {"webTools":{"search":null,"fetch":null}}
                """);

        CodeAgentConfig config = CodeAgentConfig.load(configFile, Map.of());

        assertEquals("auto", config.getWebTools().getSearch().getBackend());
        assertEquals("auto", config.getWebTools().getFetch().getBackend());
    }
}
