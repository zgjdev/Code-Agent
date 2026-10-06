package com.codeagent.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryEvaluationDatasetTest {
    record QueryCase(String id, String category, String query,
                     List<RetrievalEvaluationMetrics.Evidence> evidence) {}

    static List<QueryCase> load() throws Exception {
        try (var input = RepositoryEvaluationDatasetTest.class.getResourceAsStream(
                "/rag/repository-evaluation.json")) {
            assertNotNull(input);
            return new ObjectMapper().readValue(input, new TypeReference<>() {});
        }
    }

    @Test void validatesRealSourceEvidenceAndBalancedCategories() throws Exception {
        validate(Path.of("").toAbsolutePath());
    }

    static void validate(Path root) throws Exception {
        var cases = load();
        assertEquals(75, cases.size());
        var ids = new HashSet<String>();
        var queries = new HashSet<String>();
        for (var item : cases) {
            assertTrue(ids.add(item.id()), item.id());
            assertTrue(queries.add(item.query()), item.query());
            assertFalse(item.query().isBlank());
            assertEquals(item.category().equals("no_answer"), item.evidence().isEmpty());
            assertEquals(item.evidence().size(), new HashSet<>(item.evidence()).size());
            for (var evidence : item.evidence()) {
                assertTrue(evidence.path().startsWith("src/main/java/"));
                Path path = root.resolve(evidence.path()).normalize();
                assertTrue(path.startsWith(root));
                String source = Files.readString(path);
                int first = source.indexOf(evidence.marker());
                assertTrue(first >= 0, item.id() + " missing marker: " + evidence.marker());
                assertEquals(-1, source.indexOf(evidence.marker(), first + 1),
                        item.id() + " ambiguous marker");
                assertFalse(evidence.requiredText().isEmpty(), "body evidence required");
                for (String body : evidence.requiredText())
                    assertTrue(source.contains(body), item.id() + " missing body evidence: " + body);
            }
        }
        for (String category : List.of("identifier", "semantic", "paraphrase"))
            assertEquals(19, cases.stream().filter(c -> c.category().equals(category)).count());
        assertEquals(6, cases.stream().filter(c -> c.category().equals("cross_module")).count());
        assertEquals(6, cases.stream().filter(c -> c.category().equals("no_answer")).count());
        assertEquals(6, cases.stream().filter(c -> c.category().equals("symbol_reference")).count());
    }
}
