package com.codeagent.runtime.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceResourceLeaseTest {
    @TempDir Path temporary;

    @Test void rejectsNestedRootsUntilOwnerCloses() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("project"));
        Path child = Files.createDirectories(root.resolve("module"));
        var resolver = new WorkspaceIdentityResolver();
        Path locks = temporary.resolve("locks");
        try (var owner = WorkspaceOwnerLease.acquire(locks, resolver.resolve(root), "first")) {
            assertThrows(java.io.IOException.class,
                    () -> WorkspaceOwnerLease.acquire(locks, resolver.resolve(child), "second"));
        }
        try (var childOwner = WorkspaceOwnerLease.acquire(locks, resolver.resolve(child), "second")) {
            childOwner.verifyIdentity();
        }
    }

    @Test void sharedResourceAcquisitionIsAllOrNothing() throws Exception {
        var leases = new SharedExecutionResourceLeases(temporary.resolve("locks"));
        try (var first = leases.tryAcquire(Set.of("git:shared", "browser:profile")).orElseThrow()) {
            assertTrue(leases.tryAcquire(Set.of("git:shared", "unrelated")).isEmpty());
            try (var independent = leases.tryAcquire(Set.of("unrelated")).orElseThrow()) {
                assertNotNull(independent);
            }
        }
        try (var second = leases.tryAcquire(Set.of("git:shared", "browser:profile")).orElseThrow()) {
            assertNotNull(second);
        }
    }

    @Test void replacementOfRegisteredRootFailsClosed() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("root"));
        try (var owner = WorkspaceOwnerLease.acquire(temporary.resolve("locks"),
                new WorkspaceIdentityResolver().resolve(root), "first")) {
            Files.move(root, temporary.resolve("old-root"));
            Files.createDirectory(root);
            assertThrows(java.io.IOException.class, owner::verifyIdentity);
        }
    }
}
