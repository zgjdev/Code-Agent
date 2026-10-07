package com.codeagent.cli;

import com.codeagent.config.CodeAgentConfig;
import com.codeagent.rag.*;
import com.codeagent.rag.embedding.EmbeddingResolution;
import com.codeagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MainLocalEmbeddingTest {
    @TempDir Path project;
    @Test void localCommandSelectsAndReportsQwenWithoutLoadingWeights() {
        var config = new CodeAgentConfig() { @Override public void save() {} };
        var registry = new ToolRegistry();
        var service = new RecordingService(); registry.setCodeRetrievalService(service);
        try {
            String message = Main.handleEmbeddingConfigCommand(config, "embedding local", project, registry, null);
            assertTrue(message.contains("Qwen3"), message);
            assertEquals("local-qwen3", service.resolution.provider().orElseThrow().id());
            assertEquals("local", config.getEmbedding().getMode());
        } finally { service.close(); }
    }
    private static class RecordingService implements CodeRetrievalService {
        EmbeddingResolution resolution;
        public RetrievalResponse search(RetrievalRequest request) { throw new UnsupportedOperationException(); }
        public IndexRefreshResult refresh(IndexRefreshRequest request) { throw new UnsupportedOperationException(); }
        public RetrievalIndexStatus status() { return new RetrievalIndexStatus(true,false,0,0); }
        public void reconfigureEmbedding(EmbeddingResolution value) { resolution = value; }
        public void close() { if (resolution != null) resolution.provider().ifPresent(p -> p.close()); }
    }
}
