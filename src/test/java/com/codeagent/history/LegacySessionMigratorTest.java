package com.codeagent.history;

import com.codeagent.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacySessionMigratorTest {
    @TempDir Path tempDir;

    @Test
    void migratesMessagesAndClearWithoutDeletingSourceAndIsIdempotent() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path history = tempDir.resolve("history");
        ConversationLedger legacy = ConversationLedger.open(history, "legacy-safe");
        legacy.appendMessage("react", "agent", "attach", LlmClient.Message.system("system"));
        legacy.appendMessage("react", "agent", "input", LlmClient.Message.user("old"));
        legacy.appendEvent("history_clear", "react", "agent", "slash", Map.of());
        legacy.appendMessage("react", "agent", "input", LlmClient.Message.user("new"));

        try (SessionStore store = SessionStore.open(history)) {
            LegacySessionMigrator migrator = new LegacySessionMigrator(store, workspace);
            SessionSummary first = migrator.migrate(legacy.file());
            SessionSummary second = migrator.migrate(legacy.file());

            assertEquals(first.sessionId(), second.sessionId());
            assertTrue(Files.exists(legacy.file()));
            assertEquals(java.util.List.of("new"), store.readProjection(first.sessionId()).messages().stream()
                    .map(LlmClient.Message::content).toList());
        }
    }

    @Test
    void legacyCompactionIsMarkedUnsafeAndCannotResume() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path history = tempDir.resolve("history");
        ConversationLedger legacy = ConversationLedger.open(history, "legacy-unsafe");
        legacy.appendMessage("react", "agent", "input", LlmClient.Message.user("old"));
        legacy.appendEvent("compaction", "react", "agent", "automatic", Map.of("beforeMessages", 10));

        try (SessionStore store = SessionStore.open(history)) {
            SessionSummary migrated = new LegacySessionMigrator(store, workspace).migrate(legacy.file());

            assertTrue(migrated.resumeUnsafe());
            assertThrows(SessionStore.ResumeUnsafeException.class,
                    () -> store.resumeWritable(migrated.sessionId(), workspace));
        }
    }
}
