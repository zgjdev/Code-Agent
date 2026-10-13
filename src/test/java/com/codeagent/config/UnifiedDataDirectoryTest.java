package com.codeagent.config;

import com.codeagent.plan.PlanStateStore;
import com.codeagent.policy.AuditLog;
import com.codeagent.rag.embedding.InProcessQwen3EmbeddingProvider;
import com.codeagent.runtime.api.RuntimeThreadStore;
import com.codeagent.runtime.task.DurableTaskManager;
import com.codeagent.snapshot.SnapshotConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedDataDirectoryTest {
    @TempDir Path temporary;
    private Properties saved;
    private Path home;
    private Path project;

    @BeforeEach void isolate() throws Exception {
        org.slf4j.LoggerFactory.getLogger(getClass());
        saved = (Properties) System.getProperties().clone();
        saved.stringPropertyNames().stream().filter(k -> k.startsWith("codeagent.")).forEach(System::clearProperty);
        home = Files.createDirectories(temporary.resolve("home"));
        project = Files.createDirectories(temporary.resolve("project"));
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", project.toString());
    }

    @AfterEach void restore() { System.setProperties(saved); }

    @Test void legacyDirectoriesCannotScatterDefaultState() throws Exception {
        Path outside = temporary.resolve("legacy");
        for (String component : new String[]{"plan", "task", "runtime", "audit", "snapshot"}) {
            System.setProperty("codeagent." + component + ".dir", outside.toString());
        }
        assertStoresUnder(home.resolve(".codeagent"));
        assertFalse(Files.exists(outside), "Legacy paths must not be created");
    }

    @Test void singleRootMovesAllDefaultStoresTogether() throws Exception {
        Path root = temporary.resolve("data with spaces");
        System.setProperty("codeagent.home", root.toString());
        assertStoresUnder(root);
        assertTrue(InProcessQwen3EmbeddingProvider.defaultModelDirectory().startsWith(root.resolve("models")));
        assertFalse(Files.exists(home.resolve(".codeagent")));
    }

    @Test void dotenvRootMovesStoresAndLegacyDotenvIsIgnored() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("CODEAGENT_HOME") == null);
        Path root = temporary.resolve("dotenv-data");
        Files.writeString(project.resolve(".env"), "CODEAGENT_HOME=" + root
                + "\nCODEAGENT_LOG_DIR=/Users/yourname/.codeagent/logs"
                + "\nCODEAGENT_TASK_DIR=/tmp/old-tasks\nCODEAGENT_AUDIT_DIR=/tmp/old-audit\n");
        assertStoresUnder(root);
    }

    @Test void ownedFilesAndUserConfigurationFollowRoot() throws Exception {
        Path root = temporary.resolve("owned-data");
        System.setProperty("codeagent.home", root.toString());
        new CodeAgentConfig().save();
        assertTrue(Files.isRegularFile(root.resolve("config.json")));
        com.codeagent.history.ConversationLedger.openDefault();
        new com.codeagent.memory.LongTermMemory();
        try (var sessions = com.codeagent.history.SessionStore.open(root.resolve("history"));
             var session = sessions.create(new com.codeagent.history.SessionStore.SessionCreateRequest(
                     project, "glm", "fixture", null, "react", "agent"));
             var vectors = new com.codeagent.rag.VectorStore(project.toString())) {
            assertTrue(Files.exists(root.resolve("memory/memory.db")));
            assertTrue(Files.exists(root.resolve("rag/codebase.db")));
            assertTrue(Files.isDirectory(root.resolve("history/raw")));
            assertTrue(Files.isDirectory(root.resolve("history/sessions").resolve(session.sessionId())));
        }
        var snapshot = new com.codeagent.tui.history.ConversationSnapshot("fixture-session");
        snapshot.appendUser("fixture");
        snapshot.save();
        assertTrue(Files.isRegularFile(root.resolve("history/fixture-session.jsonl")));
        Files.createDirectories(root.resolve("prompts/modes"));
        Files.writeString(root.resolve("prompts/modes/agent.md"), "root-prompt-fixture");
        assertEquals("root-prompt-fixture", com.codeagent.prompt.PromptRepository.createDefault().loadRequired("modes/agent.md"));
        Files.writeString(root.resolve("CODEAGENT.md"), "root-memory-fixture");
        assertTrue(com.codeagent.prompt.ProjectMemoryLoader.createDefault(project).loadForPrompt().contains("root-memory-fixture"));
        assertFalse(Files.exists(home.resolve(".codeagent")));
    }

    @Test void projectInternalDataMustNotBeIndexedOrSnapshotted() throws Exception {
        Path root = Files.createDirectories(project.resolve("private-state"));
        System.setProperty("codeagent.home", root.toString());
        Files.writeString(root.resolve("private.md"), "private application data");
        Files.writeString(project.resolve("Keep.java"), "class Keep {}");
        var scan = new com.codeagent.rag.IndexFileScanner().scan(project);
        assertTrue(scan.complete());
        assertEquals(1, scan.files().size());
        var manager = new com.codeagent.snapshot.SideGitManager(project);
        manager.preTurnSnapshot("fixture", "fixture");
        try (var repository = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                .setGitDir(manager.gitDir().toFile()).build()) {
            assertFalse(repository.readDirCache().getEntryCount() == 0);
            assertTrue(repository.readDirCache().findEntry("private-state/private.md") < 0);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"state[1]", "#state", "state with spaces"})
    void dataDirectoryIsExcludedLiterally(String name) throws Exception {
        Path root = Files.createDirectories(project.resolve(name));
        System.setProperty("codeagent.home", root.toString());
        Files.writeString(root.resolve("private.md"), "private fixture");
        Files.createDirectories(project.resolve("state1"));
        Files.writeString(project.resolve("state1/Keep.java"), "class Keep {}");
        var manager = new com.codeagent.snapshot.SideGitManager(project);
        // Probe ignore rules before staging, so a failing test cannot snapshot its own database.
        var writeExcludes = manager.getClass().getDeclaredMethod("writeExcludeFile");
        writeExcludes.setAccessible(true);
        writeExcludes.invoke(manager);
        var rules = new org.eclipse.jgit.ignore.IgnoreNode();
        try (var input = Files.newInputStream(manager.gitDir().resolve("info/exclude"))) {
            rules.parse(input);
        }
        assertEquals(org.eclipse.jgit.ignore.IgnoreNode.MatchResult.IGNORED, rules.isIgnored(name, true));
        assertNotEquals(org.eclipse.jgit.ignore.IgnoreNode.MatchResult.IGNORED, rules.isIgnored("state1", true));
        var tracker = com.codeagent.agent.TaskWorkspaceDiffTracker.start(project,
                new com.codeagent.plan.TaskResourceClaims(java.util.List.of(), java.util.List.of(), true), manager);
        assertFalse(tracker.baseline().hashes().containsKey(name + "/private.md"));
        assertTrue(tracker.baseline().hashes().containsKey("state1/Keep.java"));
        manager.preTurnSnapshot("fixture", "fixture");
        try (var repository = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                .setGitDir(manager.gitDir().toFile()).build()) {
            assertTrue(repository.readDirCache().findEntry(name + "/private.md") < 0);
            assertTrue(repository.readDirCache().findEntry("state1/Keep.java") >= 0);
        }
    }

    private void assertStoresUnder(Path root) throws Exception {
        assertEquals(root.resolve("tasks/tasks.db"), DurableTaskManager.defaultDbPath());
        assertEquals(root.resolve("runtime/runtime.db"), RuntimeThreadStore.defaultDbPath());
        assertEquals(root.resolve("audit"), new AuditLog().getAuditDir());
        assertEquals(root.resolve("snapshots"), SnapshotConfig.fromEnvironment().snapshotsRoot());
        assertEquals(root.resolve("plans/plans.db"), PlanStateStore.openDefault().dbPath());
    }
}
