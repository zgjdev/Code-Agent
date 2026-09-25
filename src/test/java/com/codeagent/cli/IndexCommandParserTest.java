package com.codeagent.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IndexCommandParserTest {
    @Test void parsesActionsAndLegacyPath(@TempDir Path root) {
        IndexCommandParser parser = new IndexCommandParser();
        assertEquals(IndexCommandParser.IndexCommand.Action.REFRESH, parser.parse(null).action());
        assertEquals(IndexCommandParser.IndexCommand.Action.STATUS, parser.parse("status").action());
        assertEquals(IndexCommandParser.IndexCommand.Action.REBUILD, parser.parse("rebuild").action());
        assertEquals(root.toString(), parser.parse(root.toString()).path());
    }

    @Test void rejectsUnknownNonexistentValue() {
        assertThrows(IllegalArgumentException.class,
                () -> new IndexCommandParser().parse("definitely-not-a-real-index-action"));
    }
}
