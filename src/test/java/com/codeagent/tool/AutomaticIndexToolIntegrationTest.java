package com.codeagent.tool;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.rag.*;
import com.codeagent.rag.embedding.EmbeddingResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticIndexToolIntegrationTest {
    @Test void explicitAttachAndWriteNotification(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        var registry = new ToolRegistry(); registry.setProjectPath(root.toString());
        var config = new CodeAgentConfig(); config.getAutoIndex().setDebounceMillis(0);
        try (var index = new SqliteRetrievalIndex(temp.resolve("index.db"));
             var service = new DefaultCodeRetrievalService(index, new EmbeddingResolution(Optional.empty(), "off", false))) {
            registry.setCodeRetrievalService(service);
            assertEquals("disabled", service.maintenanceStatus(root).state());
            try (var manager = registry.startAutomaticIndex(config)) {
                manager.awaitIdle(root, Duration.ofSeconds(5));
                var output = registry.executeToolOutput("write_file", "{\"path\":\"Store.java\",\"content\":\"class Store { void saveData() {} }\"}");
                assertTrue(output.successful());
                manager.awaitIdle(root, Duration.ofSeconds(5));
                assertFalse(index.searchTerms(root, "saveData", 10).isEmpty());
            }
        }
    }
}
