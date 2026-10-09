package com.codeagent.rag;

import java.nio.file.Path;

/**
 * Bounded queue metadata; source content is read only when work starts.
 */
public record EmbeddingWorkItem(Path projectRoot, Path relativePath, String expectedContentHash, String embeddingSpaceId) {
}
