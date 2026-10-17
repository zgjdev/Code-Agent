package com.codeagent.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StartupCommandParserTest {
    @Test void staticEntrypointsAndServe() {
        assertEquals(StartupCommandParser.Kind.HELP, StartupCommandParser.parse(new String[]{"--help"}).kind());
        assertEquals(StartupCommandParser.Kind.VERSION, StartupCommandParser.parse(new String[]{"--version"}).kind());
        assertEquals(StartupCommandParser.Kind.CONFIG, StartupCommandParser.parse(new String[]{"config"}).kind());
        assertEquals(StartupCommandParser.Kind.INTERACTIVE, StartupCommandParser.parse(new String[]{}).kind());
        assertEquals(StartupCommandParser.Kind.SERVE,
                StartupCommandParser.parse(new String[]{"serve", "--http", "--port", "8123"}).kind());
    }

    @Test void refusesUnknownArgumentsWithoutRetainingSecret() {
        for (String[] args : new String[][]{{"task"}, {"--api-key", "fixture-secret"},
                {"--help", "fixture-secret"}, {"serve", "--http", "--port", "oops"},
                {"serve", "--http", "--port", "65536"}, {"serve"}}) {
            var parsed = StartupCommandParser.parse(args);
            assertEquals(StartupCommandParser.Kind.ERROR, parsed.kind());
            assertFalse(parsed.toString().contains("fixture-secret"));
        }
    }
}