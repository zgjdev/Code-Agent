package com.codeagent.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalPackagingTest {
    @Test
    void runtimeClasspathContainsBundledModelAndNativeLibraries() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        assertNotNull(loader.getResource("bge-small-zh-v1.5-q.onnx"));
        assertNotNull(loader.getResource("bge-small-zh-v1.5-q-tokenizer.json"));
        assertNotNull(loader.getResource("ai/onnxruntime/native/win-x64/onnxruntime.dll"));
        assertNotNull(loader.getResource("ai/onnxruntime/native/linux-x64/libonnxruntime.so"));
        assertNotNull(loader.getResource("ai/onnxruntime/native/osx-aarch64/libonnxruntime.dylib"));
    }

    @Test
    void buildDeclaresShadeMainClassAndPinnedEmbeddingArtifact() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("maven-shade-plugin"));
        assertTrue(pom.contains("com.codeagent.cli.Main"));
        assertTrue(pom.contains("langchain4j-embeddings-bge-small-zh-v15-q"));
        assertTrue(pom.contains("1.18.0-beta28"));
    }
}
