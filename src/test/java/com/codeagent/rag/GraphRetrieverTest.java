package com.codeagent.rag;

import com.codeagent.rag.stage.GraphRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphRetrieverTest {
    @Test
    void resolvesGraphCandidatesThroughInternalSymbolSeeds(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project")).toRealPath();
        String fileId = StableSymbolId.create("src/A.java", "FILE", "src/A.java", "");
        String classId = StableSymbolId.create("src/A.java", "CLASS", "p.A", "");
        FileIndexBatch batch = new FileIndexBatch(project, Path.of("src/A.java"), "hash", 10, 1,
                "java", "READY", null,
                List.of(new IndexedChunk(1, 3, "class", "A", classId,
                        "class A extends B {}", "class a extends b", "hash")),
                List.of(
                        new IndexedSymbol(fileId, "src/A.java", "A.java", "", "FILE", null, 1, 3),
                        new IndexedSymbol(classId, "p.A", "A", "", "CLASS", fileId, 1, 3)),
                List.of(new IndexedRelation(classId, null, "B", "extends", 1)));

        try (SqliteRetrievalIndex index = new SqliteRetrievalIndex(temp.resolve("codebase-v2.db"))) {
            index.replaceLexicalFile(batch);
            RetrievalContext context = new RetrievalContext(
                    new RetrievalRequest(project, "A", 5, 4_000, false, RetrievalIntent.CHUNKS),
                    index, Optional.empty());

            List<RetrievalCandidate> hits = new GraphRetriever().retrieve(context);

            assertFalse(index.searchSymbols(project, "A", 5).isEmpty(),
                    "the symbol index remains graph infrastructure");
            assertFalse(hits.isEmpty());
            assertEquals(classId, hits.get(0).symbolId());
            assertEquals("extends", hits.get(0).relationType());
            assertTrue(hits.stream().allMatch(hit -> hit.symbolId() != null));
        }
    }
}
