package com.codeagent.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingConfigCommandParserTest {
    private final EmbeddingConfigCommandParser parser = new EmbeddingConfigCommandParser();

    @Test void parsesSupportedActions() {
        assertEquals(EmbeddingConfigCommandParser.EmbeddingConfigCommand.Action.STATUS,
                parser.parse("embedding").action());
        assertEquals(EmbeddingConfigCommandParser.EmbeddingConfigCommand.Action.LOCAL,
                parser.parse("embedding local").action());
        assertEquals(EmbeddingConfigCommandParser.EmbeddingConfigCommand.Action.OFF,
                parser.parse("embedding off").action());
        assertEquals("glm", parser.parse("embedding remote glm").provider());
        assertEquals(EmbeddingConfigCommandParser.EmbeddingConfigCommand.Action.REVOKE,
                parser.parse("embedding revoke").action());
    }

    @Test void rejectsRemovedAndUnknownProvidersThroughSamePath() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("embedding ollama"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("embedding remote unknown"));
    }
}
