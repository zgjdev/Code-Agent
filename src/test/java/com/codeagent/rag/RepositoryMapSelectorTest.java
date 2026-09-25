package com.codeagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryMapSelectorTest {
    @Test void respectsTokenBudgetAndMarksPartial(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        for (int i = 0; i < 8; i++) Files.writeString(root.resolve("Type" + i + ".java"),
                "class Type" + i + " { void method" + i + "() {} }");
        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(temp.resolve("v2.db"))) {
            new IndexCoordinator(index, Optional.empty()).refresh(new IndexRefreshRequest(root, false));
            RepositoryMap map = new RepositoryMapSelector(index).select(root, "Type7", 20);
            assertTrue(map.estimatedTokens() <= 20);
            assertTrue(map.partial());
            assertTrue(map.text().contains("Type7"));
        }
    }
}
