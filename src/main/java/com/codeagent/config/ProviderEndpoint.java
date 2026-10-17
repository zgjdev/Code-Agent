package com.codeagent.config;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;

/** Shared local validation before displaying, saving or testing a provider address. */
public final class ProviderEndpoint {
    private ProviderEndpoint() { }

    public static URI validate(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !("http".equalsIgnoreCase(scheme) && isLoopback(host)))
                throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid provider endpoint");
        }
    }

    private static boolean isLoopback(String host) {
        String normalized = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (normalized.equals("localhost") || normalized.equals("::1") || normalized.equals("0:0:0:0:0:0:0:1")) return true;
        return normalized.matches("127(?:\\.\\d{1,3}){3}")
                && Arrays.stream(normalized.split("\\.")).allMatch(part -> Integer.parseInt(part) <= 255);
    }
}
