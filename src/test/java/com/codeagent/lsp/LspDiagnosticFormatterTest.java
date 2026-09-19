package com.codeagent.lsp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LspDiagnosticFormatterTest {
    @Test
    void countsErrorsAndWarningsIndependentOfDisplayLimit() {
        LspDiagnosticReport report = LspDiagnosticFormatter.format(List.of(
                new LspDiagnostic(LspSeverity.ERROR, "A.java", 1, 1, "broken", "test"),
                new LspDiagnostic(LspSeverity.WARNING, "A.java", 2, 1, "warn", "test"),
                new LspDiagnostic(LspSeverity.ERROR, "B.java", 3, 1, "broken", "test")));

        assertEquals(2, report.errorCount());
        assertEquals(1, report.warningCount());
    }
}
