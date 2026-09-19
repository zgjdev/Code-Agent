package com.codeagent.lsp;

public record LspDiagnosticReport(String promptText, String displayText,
                                  int errorCount, int warningCount) {
    public LspDiagnosticReport(String promptText, String displayText) {
        this(promptText, displayText, 0, 0);
    }

    public static final LspDiagnosticReport EMPTY = new LspDiagnosticReport("", "", 0, 0);

    public boolean isEmpty() {
        return promptText == null || promptText.isBlank();
    }
}
