package com.codeagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRagMigrationTest {
    @Test
    void openingV2LeavesLegacyDatabaseBytesUntouched(@TempDir Path temp) throws Exception {
        Path legacy = temp.resolve("codebase.db");
        Files.write(legacy, new byte[]{1, 2, 3, 4, 5});
        byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(legacy));

        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(temp.resolve("codebase-v2.db"), legacy)) {
            assertTrue(index.status(temp).legacyDatabasePresent());
        }

        assertArrayEquals(before,
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(legacy)));
        assertTrue(Files.exists(temp.resolve("codebase-v2.db")));
    }
}
