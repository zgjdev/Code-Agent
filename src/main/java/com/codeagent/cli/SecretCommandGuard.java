package com.codeagent.cli;

import java.util.regex.Pattern;
import org.jline.reader.MaskingCallback;

/** Reject legacy key arguments before submit echo, history, dispatch, or ledger writes. */
public final class SecretCommandGuard {
    private static final Pattern CONFIG = Pattern.compile("(?is)^\\p{javaWhitespace}*/config\\b.*?\\p{javaWhitespace}-*(?:api[_-]?key|key)(?=\\p{javaWhitespace}|=|:|$).*");
    private SecretCommandGuard() { }
    public static boolean rejected(String input) {
        // The command parser strips both quote styles, including quotes inside a token.
        // Apply the same normalization before conservatively rejecting legacy key fields.
        return input != null && CONFIG.matcher(input.replace("\"", "").replace("'", "")).matches();
    }
    public static String message() { return "密钥不能通过命令参数设置；请使用 /config 或 codeagent config 的隐藏输入。"; }
    static String hiddenDisplay() { return "[密钥参数已隐藏，请使用 /config]"; }
    public static MaskingCallback commandMask() {
        return new MaskingCallback() {
            public String display(String line) {
                return rejected(line) ? hiddenDisplay() : Main.redactSensitiveInput(line);
            }
            public String history(String line) { return rejected(line) ? null : line; }
        };
    }
}
