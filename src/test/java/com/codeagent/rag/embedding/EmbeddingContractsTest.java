package com.codeagent.rag.embedding;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingContractsTest {

    @Test
    void rejectsMismatchedEmbeddingSpaceId() {
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingSpaceDescriptor(
                "caller-supplied-id",
                "local-bge",
                "bge-small-zh-v1.5-q",
                "in-process",
                "1.18.0-beta28",
                512,
                "cls",
                true,
                1,
                1));
    }

    @Test
    void derivesDeterministicSpaceIdFromEveryCompatibilityField() {
        EmbeddingSpaceDescriptor first = descriptor(512, 1);
        EmbeddingSpaceDescriptor same = descriptor(512, 1);
        EmbeddingSpaceDescriptor changedDimension = descriptor(384, 1);
        EmbeddingSpaceDescriptor changedPreprocessing = descriptor(512, 2);

        assertEquals(first.embeddingSpaceId(), same.embeddingSpaceId());
        assertNotEquals(first.embeddingSpaceId(), changedDimension.embeddingSpaceId());
        assertNotEquals(first.embeddingSpaceId(), changedPreprocessing.embeddingSpaceId());
    }

    @Test
    void embeddingExceptionExposesStableReasonCode() {
        EmbeddingException exception = new EmbeddingException(
                "remote_embedding_timeout", "Remote embedding timed out");

        assertEquals("remote_embedding_timeout", exception.reasonCode());
        assertEquals("Remote embedding timed out", exception.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingException(" ", "invalid"));
    }

    @Test
    void emptyResolutionUsesSafeDefaults() {
        EmbeddingResolution resolution = new EmbeddingResolution(null, null, false);

        assertEquals(Optional.empty(), resolution.provider());
        assertEquals("unspecified", resolution.reason());
        assertTrue(resolution.provider().isEmpty());
    }

    private static EmbeddingSpaceDescriptor descriptor(int dimension, int preprocessingVersion) {
        return EmbeddingSpaceDescriptor.create(
                "local-bge",
                "bge-small-zh-v1.5-q",
                "in-process",
                "1.18.0-beta28",
                dimension,
                "cls",
                true,
                preprocessingVersion,
                1);
    }
}
