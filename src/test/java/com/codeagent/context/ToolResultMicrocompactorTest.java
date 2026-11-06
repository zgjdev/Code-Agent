package com.codeagent.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import com.codeagent.memory.MemoryEntry;

import static com.codeagent.context.HistorySnipperTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolResultMicrocompactorTest {
    @Test
    void shrinksOldUniqueCommandAndPreservesTypedFactsAndInvocation() {
        var nodes = new ArrayList<ContextNode>();
        nodes.addAll(batch(1, "old", "exec", "execute_command", "{}", "old output ".repeat(600)));
        nodes.addAll(batch(3, "recent", "exec", "read_file", "{}", "recent ".repeat(600)));
        nodes.addAll(batch(5, "latest", "exec", "read_file", "{}", "latest ".repeat(600)));
        var edit = new ToolResultMicrocompactor().propose(snapshot(nodes, List.of()), options()).orElseThrow();
        assertEquals(List.of(nodes.get(1).id()), edit.orderedSourceIds());
        var replacement = edit.replacementNodes().get(0);
        assertEquals("old", replacement.message().toolCallId());
        assertEquals(nodes.get(1).descriptor(), replacement.descriptor());
        assertTrue(replacement.message().content().contains("mvn test"));
        assertTrue(replacement.message().content().contains("TRUNCATED"));
        assertTrue(replacement.message().content().contains(nodes.get(1).descriptor().contentHash()));
    }

    @Test
    void skipsFullBodyProofAndRecentResults() {
        var nodes = new ArrayList<ContextNode>();
        String body = "same output ".repeat(600);
        nodes.addAll(batch(1, "older", "exec", "read_file", "{}", body));
        nodes.addAll(batch(3, "proof", "exec", "read_file", "{}", body));
        nodes.addAll(batch(5, "recent", "exec", "read_file", "{}", body + "2"));
        nodes.addAll(batch(7, "latest", "exec", "read_file", "{}", body + "3"));
        var edit = new HistorySnipper().propose(snapshot(nodes, List.of()), options()).orElseThrow();
        assertTrue(new ToolResultMicrocompactor().propose(snapshot(nodes, List.of(edit)), options()).isEmpty());
    }

    @Test
    void keepsBoundedUnicodeExcerptsAndLeavesOversizedLocatorIntact() {
        var nodes = new ArrayList<ContextNode>();
        String body = "📄".repeat(4000);
        nodes.addAll(batch(1, "oversized", "exec", "read_file", "{\"path\":\"" + "x".repeat(9000) + "\"}", body));
        nodes.addAll(batch(3, "eligible", "exec", "read_file", "{\"path\":\"b\",\"offset\":7}", body));
        nodes.addAll(batch(5, "recent", "exec", "read_file", "{\"path\":\"c\"}", body));
        nodes.addAll(batch(7, "latest", "exec", "read_file", "{\"path\":\"d\"}", body));
        var edit = new ToolResultMicrocompactor().propose(snapshot(nodes, List.of()), options()).orElseThrow();
        assertEquals(List.of(nodes.get(3).id()), edit.orderedSourceIds());
        String shortened = edit.replacementNodes().get(0).message().content();
        assertTrue(shortened.contains("\"offset\":7"));
        assertTrue(shortened.contains("📄"));
        assertTrue(MemoryEntry.estimateTokens(shortened) <= 768);
        assertFalse(shortened.contains("📄".repeat(257)));
    }
}
