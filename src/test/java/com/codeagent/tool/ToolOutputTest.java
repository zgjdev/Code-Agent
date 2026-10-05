package com.codeagent.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ToolOutputTest {
    @Test
    void structuredContentCannotBeMutatedThroughInputOrAccessor() {
        var data = JsonNodeFactory.instance.objectNode().put("code", 0);
        var out = new ToolOutput("body", List.of(), true, List.of(), ToolOutput.FailureKind.NONE, data);
        data.put("code", 1);
        var copy = (com.fasterxml.jackson.databind.node.ObjectNode) out.structuredContent();
        copy.put("code", 2);
        assertEquals(0, out.structuredContent().path("code").asInt());
        assertNull(ToolOutput.text("body").structuredContent());
    }
}
