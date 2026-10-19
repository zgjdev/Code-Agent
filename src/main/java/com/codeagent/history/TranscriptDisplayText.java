package com.codeagent.history;

import com.codeagent.util.SecretRedactor;

/** Sanitizes before redaction so terminal escapes cannot split recognizable credentials. */
final class TranscriptDisplayText {
    private TranscriptDisplayText() { }

    static String sanitize(String input) {
        return SecretRedactor.sanitize(input);
    }
}
