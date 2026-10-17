package com.codeagent.cli;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.config.UserConfigStore;
import com.codeagent.hitl.HitlHandler;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MainConfigMutationTest {
    @TempDir Path project;

    private CodeAgentConfig failingConfiguration() {
        return new CodeAgentConfig() {
            @Override public void save() {
                throw new UserConfigStore.ConfigStoreException(UserConfigStore.Failure.IO);
            }
        };
    }

    @Test void modelSaveFailureRestoresDefaultAndEditedModel() {
        CodeAgentConfig config = failingConfiguration();
        config.setDefaultProvider("step");
        var original = new CodeAgentConfig.ProviderConfig("fixture-key", "https://example.test/v1", "original-model");
        original.setLoraId("unused");
        config.getProviders().put("glm", original);

        assertThrows(UserConfigStore.ConfigStoreException.class,
                () -> Main.switchModelConfiguration(config, Main.resolveModelSelection("glm-5.1")));

        assertEquals("step", config.getDefaultProvider());
        assertSame(original, config.getProviders().get("glm"));
        assertEquals("original-model", original.getModel());
    }

    @Test void embeddingSaveFailureRestoresTheActiveConfigurationAndDoesNotReconfigure() {
        CodeAgentConfig config = failingConfiguration();
        var original = config.getEmbedding();
        original.setMode("remote");
        original.setProvider("glm");
        original.setModel("original-model");
        original.setApiKey("fixture-key");
        ToolRegistry tools = mock(ToolRegistry.class);

        String result = Main.handleEmbeddingConfigCommand(config, "embedding off", project,
                tools, mock(HitlHandler.class));

        assertTrue(result.contains("未保存"));
        assertSame(original, config.getEmbedding());
        assertEquals("remote", original.getMode());
        assertEquals("glm", original.getProvider());
        assertEquals("original-model", original.getModel());
        assertEquals("fixture-key", original.getApiKey());
        verifyNoInteractions(tools);
    }
}
