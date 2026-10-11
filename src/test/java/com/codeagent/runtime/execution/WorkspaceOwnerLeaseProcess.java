package com.codeagent.runtime.execution;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkspaceOwnerLeaseProcess {
    private WorkspaceOwnerLeaseProcess() {}

    public static void main(String[] args) throws Exception {
        Path locks = Path.of(args[0]);
        Path workspace = Path.of(args[1]);
        Path ready = Path.of(args[2]);
        WorkspaceIdentity identity = new WorkspaceIdentityResolver().resolve(workspace);
        try (WorkspaceOwnerLease ignored = WorkspaceOwnerLease.acquire(locks, identity, "child-owner")) {
            Files.writeString(ready, "locked", StandardCharsets.UTF_8);
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        }
    }
}
