package com.codeagent.rag;

import com.codeagent.policy.PathGuard;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Checks only selected files, without rebuilding an index or treating old line numbers as current. */
public final class RetrievalFreshnessChecker {
    public Map<String, String> check(Path root, Map<String, String> selectedFileHashes, List<RetrievalHit> hits) {
        Map<String, String> states = new LinkedHashMap<>();
        PathGuard guard = new PathGuard(root.toString());
        for (var hit : hits) {
            if (states.containsKey(hit.filePath())) continue;
            String state;
            try {
                Path file = guard.resolveSafe(hit.filePath());
                String expectedHash = selectedFileHashes.get(hit.filePath());
                if (!Files.isRegularFile(file)) state = "missing";
                else if (expectedHash == null) state = "unavailable";
                else {
                    var digest = MessageDigest.getInstance("SHA-256");
                    try (var input = Files.newInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
                    }
                    state = HexFormat.of().formatHex(digest.digest()).equals(expectedHash)
                            ? "verified" : "changed";
                }
            } catch (Exception e) { state = "unavailable"; }
            states.put(hit.filePath(), state);
        }
        return Map.copyOf(states);
    }
}
