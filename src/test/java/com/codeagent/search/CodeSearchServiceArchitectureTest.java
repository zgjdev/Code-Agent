package com.codeagent.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CodeSearchServiceArchitectureTest {
    @Test
    void publicServiceProvidesDeterministicJavaFallback(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("B.java"), "class B { void target() {} }\n");
        Files.writeString(root.resolve("A.java"), "class A { void target() {} }\n");
        CodeSearchService service = new JavaCodeSearchService(Set.of("target"));

        CodeSearchResult result = service.search(new CodeSearchRequest(
                "target", root, root, "*.java", false, true, 0, 10, 10));

        assertEquals(2, result.matches().size());
        assertEquals("A.java", result.matches().get(0).file());
        assertFalse(result.partial());
    }

    @Test
    void searchPackageDoesNotDependOnToolOrPresentationLayers() throws Exception {
        Path source = Path.of("src/main/java/com/codeagent/search");
        String all = Files.walk(source).filter(Files::isRegularFile)
                .map(path -> {
                    try { return Files.readString(path); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }).reduce("", String::concat);
        assertFalse(all.contains("com.codeagent.tool"));
        assertFalse(all.contains("com.codeagent.render"));
        assertFalse(all.contains("com.codeagent.hitl"));
    }
}
