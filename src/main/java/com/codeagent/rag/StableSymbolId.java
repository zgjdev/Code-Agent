package com.codeagent.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public final class StableSymbolId {
    private StableSymbolId() {}

    public static String create(String relativePath, String symbolKind,
                                String qualifiedName, String signature) {
        String canonical = normalize(relativePath) + "\n"
                + normalize(symbolKind).toUpperCase(Locale.ROOT) + "\n"
                + normalize(qualifiedName) + "\n"
                + normalize(signature);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim().replace('\\', '/');
    }
}
