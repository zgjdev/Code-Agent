package com.codeagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryMapBuilderTest {
    @Test void containsSignaturesAndRelationsButNeverBodies() {
        RepositorySymbol type = new RepositorySymbol("src/A.java", "a", "A", "A", "",
                "CLASS", 1, 20);
        RepositorySymbol method = new RepositorySymbol("src/A.java", "m", "A.run", "run",
                "A.run(String)", "METHOD", 3, 5);
        RepositoryRelation unresolved = new RepositoryRelation("src/A.java", "a", null,
                "Missing", "calls", 10);
        String text = String.join("\n", new RepositoryMapBuilder()
                .buildLines(List.of(type, method), List.of(unresolved), "A"));
        assertTrue(text.contains("class A"));
        assertTrue(text.contains("method A.run(String)"));
        assertTrue(text.contains("Missing (unresolved)"));
        assertFalse(text.contains("{"));
    }

    @Test void onlyResolvedRelationsContributeToIndegreeOrdering() {
        RepositorySymbol low = new RepositorySymbol("src/Z.java", "low", "Z", "Z", "",
                "CLASS", 1, 2);
        RepositorySymbol high = new RepositorySymbol("src/A.java", "high", "A", "A", "",
                "CLASS", 1, 2);
        RepositoryRelation resolved = new RepositoryRelation("src/Z.java", "low", "high",
                "A", "calls", 1);
        RepositoryRelation unresolved = new RepositoryRelation("src/A.java", "high", null,
                "Z", "calls", 1);

        List<String> lines = new RepositoryMapBuilder().buildLines(
                List.of(low, high), List.of(resolved, unresolved), "");

        assertEquals("src/A.java", lines.get(0));
        assertTrue(lines.contains("    -> calls Z (unresolved)"));
    }
}
