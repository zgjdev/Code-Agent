package com.codeagent.runtime.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceOwnerLeaseTest {
    @Test
    void rejectsSecondOwnerAndAllowsAcquisitionAfterRelease(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        WorkspaceIdentity identity = new WorkspaceIdentityResolver().resolve(workspace);
        Path locks = tempDir.resolve("locks");

        WorkspaceOwnerLease first = WorkspaceOwnerLease.acquire(locks, identity, "owner-1");
        try {
            assertThrows(WorkspaceOwnerLease.WorkspaceAlreadyOwnedException.class,
                    () -> WorkspaceOwnerLease.acquire(locks, identity, "owner-2"));
        } finally {
            first.close();
        }

        try (WorkspaceOwnerLease reacquired = WorkspaceOwnerLease.acquire(locks, identity, "owner-2")) {
            assertEquals("owner-2", reacquired.ownerId());
            assertEquals(identity, reacquired.identity());
        }
    }

    @Test
    void operatingSystemLockExcludesAnotherJvm(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path locks = tempDir.resolve("locks");
        Path ready = tempDir.resolve("ready.txt");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                WorkspaceOwnerLeaseProcess.class.getName(), locks.toString(), workspace.toString(), ready.toString())
                .redirectErrorStream(true)
                .start();
        try {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (!Files.exists(ready) && child.isAlive() && Instant.now().isBefore(deadline)) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(ready), "child JVM must report that it owns the workspace lock");
            WorkspaceIdentity identity = new WorkspaceIdentityResolver().resolve(workspace);
            assertThrows(WorkspaceOwnerLease.WorkspaceAlreadyOwnedException.class,
                    () -> WorkspaceOwnerLease.acquire(locks, identity, "parent-owner"));
        } finally {
            try (BufferedWriter input = new BufferedWriter(
                    new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8))) {
                input.write("release");
                input.newLine();
                input.flush();
            }
            if (!child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor();
            }
        }
        assertEquals(0, child.exitValue());
    }
}
