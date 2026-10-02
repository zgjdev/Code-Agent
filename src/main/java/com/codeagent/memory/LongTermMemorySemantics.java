package com.codeagent.memory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class LongTermMemorySemantics {
    static final String STATUS_ACTIVE = "active";
    static final String STATUS_SUPERSEDED = "superseded";
    static final String LAST_CONFIRMED_AT = "lastConfirmedAt";

    private LongTermMemorySemantics() {
    }

    static boolean isVisibleInProject(MemoryEntry entry, String projectKey) {
        String scope = scopeOf(entry);
        if ("global".equals(scope)) {
            return true;
        }
        String entryProject = entry.getMetadata().get("project");
        return projectKey != null
                && !projectKey.isBlank()
                && Objects.equals(entryProject, projectKey);
    }

    static String scopeOf(MemoryEntry entry) {
        String scope = entry.getMetadata().get("scope");
        return "project".equalsIgnoreCase(scope) ? "project" : "global";
    }

    static boolean isActive(MemoryEntry entry) {
        if (entry == null) {
            return false;
        }
        String status = entry.getMetadata().get("status");
        return status == null
                || status.isBlank()
                || STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    static String statusOf(MemoryEntry entry) {
        return isActive(entry) ? STATUS_ACTIVE : STATUS_SUPERSEDED;
    }

    static boolean hasValidPersistedConfirmation(MemoryEntry entry) {
        String value = entry.getMetadata().get(LAST_CONFIRMED_AT);
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Instant parsed = Instant.parse(value);
            return !parsed.isBefore(entry.getTimestamp());
        } catch (Exception ignored) {
            return false;
        }
    }

    static Instant lastConfirmedAtOf(MemoryEntry entry) {
        if (entry == null) {
            return Instant.EPOCH;
        }
        Instant createdAt = entry.getTimestamp();
        String value = entry.getMetadata().get(LAST_CONFIRMED_AT);
        if (value != null && !value.isBlank()) {
            try {
                Instant parsed = Instant.parse(value);
                return parsed.isAfter(createdAt) ? parsed : createdAt;
            } catch (Exception ignored) {
                // Legacy/corrupted metadata falls back to immutable creation time.
            }
        }
        return createdAt;
    }

    static MemoryEntry withStatusAndConfirmation(MemoryEntry entry, String status) {
        Map<String, String> metadata = new HashMap<>(entry.getMetadata());
        metadata.put("status", status);
        metadata.put(LAST_CONFIRMED_AT, lastConfirmedAtOf(entry).toString());
        return copyWithMetadata(entry, metadata);
    }

    static MemoryEntry copyWithMetadata(MemoryEntry entry, Map<String, String> metadata) {
        return new MemoryEntry(
                entry.getId(),
                entry.getContent(),
                entry.getType(),
                entry.getTimestamp(),
                Map.copyOf(metadata),
                entry.getTokenCount());
    }
}
